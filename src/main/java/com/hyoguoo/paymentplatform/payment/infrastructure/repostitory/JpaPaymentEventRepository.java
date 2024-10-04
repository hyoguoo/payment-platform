package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {

    Optional<PaymentEventEntity> findByOrderId(String orderId);

    @Query("SELECT pe FROM PaymentEventEntity pe WHERE ((pe.status = 'IN_PROGRESS' AND pe.executedAt < :before) OR pe.status = 'UNKNOWN') AND pe.retryCount < :retryableLimit")
    List<PaymentEventEntity> findByInProgressWithTimeConstraintOrUnknown(@Param("before") LocalDateTime before, int retryableLimit);
}
