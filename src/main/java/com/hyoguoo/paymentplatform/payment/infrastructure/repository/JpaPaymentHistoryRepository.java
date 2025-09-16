package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPaymentHistoryRepository extends JpaRepository<PaymentHistoryEntity, Long> {

}
