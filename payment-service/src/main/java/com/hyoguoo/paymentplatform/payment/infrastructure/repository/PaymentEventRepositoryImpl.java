package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        List<PaymentOrderEntity> savedOrderEntities = paymentEvent.getPaymentOrderList().stream()
                .map(order -> jpaPaymentOrderRepository.save(PaymentOrderEntity.from(order)))
                .toList();

        PaymentEventEntity savedEventEntity = jpaPaymentEventRepository.save(PaymentEventEntity.from(paymentEvent));

        return savedEventEntity.toDomain(
                savedOrderEntities.stream()
                        .map(PaymentOrderEntity::toDomain)
                        .toList()
        );
    }

    @Override
    public List<PaymentEvent> findReadyPaymentsOlderThan(Instant before) {
        // D3 — Instant 를 직접 native query 에 전달. Hibernate 가 hibernate.jdbc.time_zone=UTC
        // 기준으로 UTC Calendar 바인딩하므로 JdbcTemplate(connectionTimeZone=UTC) 과 동일 UTC 기준으로 비교된다.
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
    public long countByStatusAndExecutedAtBefore(PaymentEventStatus status, Instant before) {
        return jpaPaymentEventRepository.countByStatusAndExecutedAtBefore(status, before);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByRetryCountGreaterThanEqual(int retryCount) {
        return jpaPaymentEventRepository.countByRetryCountGreaterThanEqual(retryCount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentEvent> findInProgressOlderThan(Instant before) {
        return jpaPaymentEventRepository
                .findInProgressOlderThan(before)
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
    public List<PaymentEvent> findAllByStatus(PaymentEventStatus status) {
        return jpaPaymentEventRepository
                .findByStatus(status)
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
}
