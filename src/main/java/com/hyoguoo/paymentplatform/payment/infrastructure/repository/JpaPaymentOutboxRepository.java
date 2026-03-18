package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentOutboxRepository extends JpaRepository<PaymentOutboxEntity, Long> {

    Optional<PaymentOutboxEntity> findByOrderId(String orderId);

    @Query("SELECT e FROM PaymentOutboxEntity e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
    List<PaymentOutboxEntity> findPendingBatch(Pageable pageable);

    @Query("SELECT e FROM PaymentOutboxEntity e WHERE e.status = 'IN_FLIGHT' AND e.inFlightAt < :before")
    List<PaymentOutboxEntity> findTimedOutInFlight(@Param("before") LocalDateTime before);
}
