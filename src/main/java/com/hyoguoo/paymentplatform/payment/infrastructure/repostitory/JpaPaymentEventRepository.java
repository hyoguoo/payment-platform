package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {

}
