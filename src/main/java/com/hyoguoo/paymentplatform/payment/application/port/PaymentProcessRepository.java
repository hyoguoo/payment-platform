package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import java.util.List;
import java.util.Optional;

public interface PaymentProcessRepository {

    PaymentProcess save(PaymentProcess paymentProcess);

    Optional<PaymentProcess> findByOrderId(String orderId);

    List<PaymentProcess> findAllByStatus(PaymentProcessStatus status);

    boolean existsByOrderId(String orderId);
}
