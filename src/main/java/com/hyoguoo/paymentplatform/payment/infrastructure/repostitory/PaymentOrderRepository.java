package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.Optional;

public interface PaymentOrderRepository {

    Optional<PaymentOrder> findById(Long id);

    PaymentOrder saveOrUpdate(PaymentOrder paymentOrder);
}
