package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentOutboxRepositoryImpl implements PaymentOutboxRepository {

    private final JpaPaymentOutboxRepository jpaPaymentOutboxRepository;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Override
    public PaymentOutbox save(PaymentOutbox paymentOutbox) {
        PaymentOutboxEntity entity = PaymentOutboxEntity.from(paymentOutbox);
        return jpaPaymentOutboxRepository.save(entity).toDomain();
    }

    @Override
    public Optional<PaymentOutbox> findByOrderId(String orderId) {
        return jpaPaymentOutboxRepository.findByOrderId(orderId)
                .map(PaymentOutboxEntity::toDomain);
    }

    @Override
    public List<PaymentOutbox> findPendingBatch(int limit) {
        LocalDateTime now = localDateTimeProvider.now();
        return jpaPaymentOutboxRepository
                .findPendingBatch(now, PageRequest.of(0, limit, Sort.unsorted()))
                .stream()
                .map(PaymentOutboxEntity::toDomain)
                .toList();
    }

    @Override
    public List<PaymentOutbox> findTimedOutInFlight(LocalDateTime before) {
        return jpaPaymentOutboxRepository.findTimedOutInFlight(before)
                .stream()
                .map(PaymentOutboxEntity::toDomain)
                .toList();
    }

    @Override
    public boolean claimToInFlight(String orderId, LocalDateTime inFlightAt) {
        return jpaPaymentOutboxRepository.claimToInFlight(
                orderId, inFlightAt, PaymentOutboxStatus.IN_FLIGHT, PaymentOutboxStatus.PENDING, inFlightAt) > 0;
    }

}
