package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PaymentOrderRepository {

    Optional<PaymentOrder> findById(Long id);

    Optional<PaymentOrder> findByOrderId(String orderId);

    Page<PaymentOrder> findAll(Pageable pageable);

    PaymentOrder saveOrUpdate(PaymentOrder paymentOrder);
}
