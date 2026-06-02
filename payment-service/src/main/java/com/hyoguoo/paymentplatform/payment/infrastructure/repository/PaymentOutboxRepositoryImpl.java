package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final Clock clock;

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
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return jpaPaymentOutboxRepository
                .findPendingBatch(now, PageRequest.of(0, limit, Sort.unsorted()))
                .stream()
                .map(PaymentOutboxEntity::toDomain)
                .toList();
    }

    @Override
    public List<PaymentOutbox> findTimedOutInFlight(Instant before) {
        return jpaPaymentOutboxRepository
                .findTimedOutInFlight(toLocalDateTime(before))
                .stream()
                .map(PaymentOutboxEntity::toDomain)
                .toList();
    }

    @Override
    public boolean claimToInFlight(String orderId, Instant inFlightAt) {
        LocalDateTime ldt = toLocalDateTime(inFlightAt);
        return jpaPaymentOutboxRepository.claimToInFlight(
                orderId, ldt, PaymentOutboxStatus.IN_FLIGHT, PaymentOutboxStatus.PENDING, ldt) > 0;
    }

    @Override
    public long countPending() {
        return jpaPaymentOutboxRepository.countPending();
    }

    @Override
    public long countFuturePending(Instant now) {
        return jpaPaymentOutboxRepository.countFuturePending(toLocalDateTime(now));
    }

    @Override
    public Optional<Instant> findOldestPendingCreatedAt() {
        return jpaPaymentOutboxRepository.findOldestPendingCreatedAt()
                .map(ldt -> ldt.toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
