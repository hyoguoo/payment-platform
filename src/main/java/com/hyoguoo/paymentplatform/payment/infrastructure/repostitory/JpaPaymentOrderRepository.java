package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

}
