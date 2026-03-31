package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;

public interface PaymentGatewayStrategy {

    boolean supports(PaymentGatewayType type);

    PaymentConfirmResult confirm(PaymentConfirmRequest request);

    PaymentCancelResult cancel(PaymentCancelRequest request);

    // 현재 미사용 — 향후 정산/대사(reconciliation) 용도로 예약
    PaymentStatusResult getStatus(String paymentKey);

    // 현재 미사용 — 향후 정산/대사(reconciliation) 용도로 예약
    PaymentStatusResult getStatusByOrderId(String orderId);
}
