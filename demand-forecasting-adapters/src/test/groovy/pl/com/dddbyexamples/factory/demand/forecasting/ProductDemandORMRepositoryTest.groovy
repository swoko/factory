package pl.com.dddbyexamples.factory.demand.forecasting

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Commit
import pl.com.dddbyexamples.factory.demand.forecasting.AdjustDemand
import pl.com.dddbyexamples.factory.demand.forecasting.Adjustment
import pl.com.dddbyexamples.factory.demand.forecasting.Demand
import pl.com.dddbyexamples.factory.demand.forecasting.DemandEvents
import pl.com.dddbyexamples.factory.demand.forecasting.DemandValue
import pl.com.dddbyexamples.factory.demand.forecasting.ReviewPolicy
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.DemandDao
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.DemandEntity
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.ProductDemandDao
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.ProductDemandEntity
import spock.lang.Specification

import javax.persistence.EntityManager
import javax.transaction.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@SpringBootTest
@Transactional
@Commit
class ProductDemandORMRepositoryTest extends Specification {

    def clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
    def events = Mock(DemandEvents)
    @Autowired
    EntityManager em
    @Autowired
    ProductDemandDao rootDao
    @Autowired
    DemandDao demandDao

    ProductDemandORMRepository repository

    final def today = LocalDate.now(clock)
    final def refNo = "3009000"

    def setup() {
        demandDao.deleteAllInBatch()
        rootDao.deleteAllInBatch()
        repository = new ProductDemandORMRepository(
                clock,
                events,
                ReviewPolicy.BASIC,
                em,
                rootDao,
                demandDao
        )
    }

    def "persists new demand"() {
        given:
        noDemandsInDB()

        when:
        def object = demandIsLoadedFromDB()
        object.adjust(demandAdjustment(today, 2000))
        repository.save(object)

        then:
        def demandsInDB = demandDao.findAll()
        demandsInDB.size() == 1
        demandsInDB.every hasAdjustment(2000)
    }

    def "updates existing demand"() {
        given:
        demandInDB((today): 1000)

        when:
        def object = demandIsLoadedFromDB()
        object.adjust(demandAdjustment(today, 2000))
        repository.save(object)

        then:
        def demandsInDB = demandDao.findAll()
        demandsInDB.size() == 1
        demandsInDB.every hasAdjustment(2000)
    }

    def "doesn't fetch historical data"() {
        given:
        demandInDB((today.minusDays(1)): 10000, (today): 1000)

        when:
        def demands = demandDao.findByRefNoAndDateGreaterThanEqual(refNo, today)

        then:
        demands.size() == 1
        demands.every { it -> it.date == today }
    }

    private ProductDemandEntity noDemandsInDB() {
        rootDao.save(new ProductDemandEntity(refNo))
    }

    private void demandInDB(Map<LocalDate, Long> demands) {
        def root = rootDao.save(new ProductDemandEntity(refNo))
        demands.each { date, level ->
            def demand = new DemandEntity(refNo, date)
            demand.setValue(new DemandValue(Demand.of(level), null))
            demandDao.save(demand)
        }
    }

    private AdjustDemand demandAdjustment(LocalDate date, long level) {
        new AdjustDemand(refNo, [
                (date): Adjustment.strong(Demand.of(level))
        ])
    }

    private ProductDemand demandIsLoadedFromDB() {
        repository.get(refNo)
    }

    private def hasAdjustment(long level) {
        return { it.getValue().getAdjustment() == Adjustment.strong(Demand.of(level)) }
    }
}
