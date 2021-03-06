package pl.com.dddbyexamples.factory.demand.forecasting;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.DemandDao;
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.DemandEntity;
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.ProductDemandDao;
import pl.com.dddbyexamples.factory.demand.forecasting.persistence.ProductDemandEntity;
import pl.com.dddbyexamples.factory.product.management.RefNoId;
import pl.com.dddbyexamples.tools.TechnicalId;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;

@Component
@AllArgsConstructor
class ProductDemandORMRepository implements ProductDemandRepository {

    private final Clock clock;
    private final DemandEvents events;
    private final ReviewPolicy reviewPolicy;
    private final EntityManager em;
    private final ProductDemandDao rootDao;
    private final DemandDao demandDao;

    @Override
    public void initNewProduct(String refNo) {
        if (rootDao.findByRefNo(refNo) == null) {
            rootDao.save(new ProductDemandEntity(refNo));
        }
    }

    @Override
    public ProductDemand get(String refNo) {
        ProductDemandEntity root = rootDao.findByRefNo(refNo);
        RefNoId id = root.createId();

        Map<LocalDate, DemandEntity> data =
                demandDao.findByRefNoAndDateGreaterThanEqual(refNo, LocalDate.now(clock)).stream()
                        .collect(toMap(
                                DemandEntity::getDate,
                                Function.identity()
                        ));

        Demands demands = new Demands();
        demands.fetch = date -> map(refNo, date, data);
        return new ProductDemand(id, new ArrayList<>(), demands, clock, events);
    }

    @Override
    public void save(ProductDemand model) {
        ProductDemandEntity root = rootDao.getOne(TechnicalId.get(model.id));
        if (model.updates.size() > 0) {
            em.lock(root, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        for (DailyDemand.DemandUpdated updated : model.updates) {
            DemandEntity entity;
            if (TechnicalId.isPersisted(updated.getId())) {
                entity = demandDao.getOne(TechnicalId.get(updated.getId()));
            } else {
                entity = new DemandEntity(
                        updated.getId().getRefNo(),
                        updated.getId().getDate()
                );
                demandDao.save(entity);
            }
            entity.setValue(new DemandValue(
                    updated.getDocumented().nullIfNone(),
                    updated.getAdjustment()
            ));
        }
    }

    private DailyDemand map(String refNo, LocalDate date,
                            Map<LocalDate, DemandEntity> data) {
        return ofNullable(data.get(date))
                .map(entity -> new DailyDemand(
                        entity.createId(),
                        reviewPolicy,
                        entity.getValue().getDocumented(),
                        entity.getValue().getAdjustment()))
                .orElseGet(() -> new DailyDemand(
                        new DemandEntity.DemandEntityId(refNo, date),
                        reviewPolicy,
                        null,
                        null
                ));
    }
}
