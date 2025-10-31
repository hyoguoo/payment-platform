package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentProcessEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;


public interface JpaPaymentProcessRepository extends JpaRepository<PaymentProcessEntity, Long> {

    Optional<PaymentProcessEntity> findByOrderId(String orderId);

    List<PaymentProcessEntity> findAllByStatus(PaymentProcessStatus status);

    boolean existsByOrderId(String orderId);
}
