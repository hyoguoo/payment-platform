package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

    List<PaymentOrderEntity> findByPaymentEventId(Long paymentEventId);
}
