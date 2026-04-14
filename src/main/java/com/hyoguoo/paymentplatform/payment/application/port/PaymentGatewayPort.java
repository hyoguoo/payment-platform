package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;

public interface PaymentGatewayPort {

    PaymentConfirmResult confirm(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException;

    PaymentCancelResult cancel(PaymentCancelRequest request);

    // 현재 미사용 — 향후 정산/대사(reconciliation) 용도로 예약
    PaymentStatusResult getStatus(String paymentKey, PaymentGatewayType gatewayType);

    // 복구 사이클(OutboxProcessingService)의 getStatus 선행 조회 경로에서 사용
    PaymentStatusResult getStatusByOrderId(String orderId, PaymentGatewayType gatewayType)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException;
}
