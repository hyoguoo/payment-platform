package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;

public interface PaymentOrderRepository {

    Optional<PaymentOrder> findById(Long id);

    PaymentOrder saveOrUpdate(PaymentOrder paymentOrder);

    List<PaymentOrder> saveAll(List<PaymentOrder> paymentOrderList);
}
