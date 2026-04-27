package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusApiResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentCheckoutService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentCheckoutService paymentCheckoutService;
    private final PaymentConfirmService paymentConfirmService;
    private final PaymentStatusService paymentStatusService;

    @PostMapping("/api/v1/payments/checkout")
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestBody CheckoutRequest checkoutRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        CheckoutCommand checkoutCommand = PaymentPresentationMapper.toCheckoutCommand(
                checkoutRequest, idempotencyKey
        );
        CheckoutResult checkoutResult = paymentCheckoutService.checkout(checkoutCommand);

        if (checkoutResult.isDuplicate()) {
            return ResponseEntity.ok(PaymentPresentationMapper.toCheckoutResponse(checkoutResult));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentPresentationMapper.toCheckoutResponse(checkoutResult));
    }

    @PostMapping("/api/v1/payments/confirm")
    public ResponseEntity<PaymentConfirmResponse> confirm(
            @RequestBody PaymentConfirmRequest paymentConfirmRequest
    ) throws PaymentOrderedProductStockException {
        PaymentConfirmCommand paymentConfirmCommand = PaymentPresentationMapper.toPaymentConfirmCommand(
                paymentConfirmRequest
        );
        PaymentConfirmAsyncResult result = paymentConfirmService.confirm(paymentConfirmCommand);

        return ResponseEntity.accepted().body(PaymentPresentationMapper.toPaymentConfirmResponse(result));
    }

    @GetMapping("/api/v1/payments/{orderId}/status")
    public ResponseEntity<PaymentStatusApiResponse> getPaymentStatus(
            @PathVariable String orderId) {
        PaymentStatusResult result = paymentStatusService.getPaymentStatus(orderId);
        return ResponseEntity.ok(PaymentPresentationMapper.toPaymentStatusApiResponse(result));
    }
}
