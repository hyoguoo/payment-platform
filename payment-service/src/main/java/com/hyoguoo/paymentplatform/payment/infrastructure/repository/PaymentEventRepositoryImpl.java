package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
        // BaseEntity.createdAt 이 LocalDateTime 이므로 JPQL 비교를 위해 UTC 변환.
        // T7(BaseEntity auditing 일원화) 전까지 임시 변환 유지.
        LocalDateTime beforeLdt = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        log.debug("findReadyPaymentsOlderThan: before={}, beforeLdt={}", before, beforeLdt);
        return jpaPaymentEventRepository
                .findReadyPaymentsOlderThan(beforeLdt)
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
