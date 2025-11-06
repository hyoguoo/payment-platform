package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentEventRepositoryImpl implements PaymentEventRepository {

    private final JpaPaymentEventRepository jpaPaymentEventRepository;
    private final JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentEvent> findById(Long id) {
        return jpaPaymentEventRepository
                .findById(id)
                .map(paymentEventEntity -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    paymentEventEntity.getId()
                            ).stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return paymentEventEntity.toDomain(paymentOrderList);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentEvent> findByOrderId(String orderId) {
        return jpaPaymentEventRepository
                .findByOrderId(orderId)
                .map(paymentEventEntity -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    paymentEventEntity.getId()
                            )
                            .stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return paymentEventEntity.toDomain(paymentOrderList);
                });
    }

    @Override
    public PaymentEvent saveOrUpdate(PaymentEvent paymentEvent) {
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();
        Stream<PaymentOrderEntity> paymentOrderEntityStream = paymentOrderList.stream()
                .map(paymentOrder ->
                        jpaPaymentOrderRepository.save(PaymentOrderEntity.from(paymentOrder))
                );
        return jpaPaymentEventRepository.save(PaymentEventEntity.from(paymentEvent))
                .toDomain(paymentOrderEntityStream.map(PaymentOrderEntity::toDomain).toList());
    }

    @Override
    public List<PaymentEvent> findDelayedInProgressOrUnknownEvents(LocalDateTime before) {
        return jpaPaymentEventRepository
                .findByInProgressWithTimeConstraintOrUnknown(before)
                .stream()
                .map(paymentEventEntity -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    paymentEventEntity.getId()
                            )
                            .stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return paymentEventEntity.toDomain(paymentOrderList);
                })
                .toList();
    }

    @Override
    public List<PaymentEvent> findReadyPaymentsOlderThan(LocalDateTime before) {
        return jpaPaymentEventRepository
                .findReadyPaymentsOlderThan(before)
                .stream()
                .map(paymentEventEntity -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    paymentEventEntity.getId()
                            )
                            .stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return paymentEventEntity.toDomain(paymentOrderList);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<PaymentEventStatus, Long> countByStatus() {
        List<Object[]> results = jpaPaymentEventRepository.countByStatusGrouped();
        Map<PaymentEventStatus, Long> statusCounts = new EnumMap<>(PaymentEventStatus.class);

        for (Object[] result : results) {
            PaymentEventStatus status = (PaymentEventStatus) result[0];
            Long count = (Long) result[1];
            statusCounts.put(status, count);
        }

        return statusCounts;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatusAndExecutedAtBefore(PaymentEventStatus status, LocalDateTime before) {
        return jpaPaymentEventRepository.countByStatusAndExecutedAtBefore(status, before);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByRetryCountGreaterThanEqual(int retryCount) {
        return jpaPaymentEventRepository.countByRetryCountGreaterThanEqual(retryCount);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<PaymentEventStatus, Map<String, Long>> countByStatusAndAgeBuckets(
            LocalDateTime fiveMinutesAgo,
            LocalDateTime thirtyMinutesAgo
    ) {
        List<Object[]> results = jpaPaymentEventRepository.countByStatusAndAgeBucketsGrouped(
                fiveMinutesAgo,
                thirtyMinutesAgo
        );

        Map<PaymentEventStatus, Map<String, Long>> statusAgeBuckets = new EnumMap<>(PaymentEventStatus.class);

        for (Object[] result : results) {
            PaymentEventStatus status = (PaymentEventStatus) result[0];
            String ageBucket = (String) result[1];
            Long count = (Long) result[2];

            statusAgeBuckets
                    .computeIfAbsent(status, k -> new HashMap<>())
                    .put(ageBucket, count);
        }

        return statusAgeBuckets;
    }

    @Override
    @Transactional(readOnly = true)
    public long countNearExpiration(LocalDateTime expirationThreshold) {
        return jpaPaymentEventRepository.countNearExpiration(expirationThreshold);
    }
}
