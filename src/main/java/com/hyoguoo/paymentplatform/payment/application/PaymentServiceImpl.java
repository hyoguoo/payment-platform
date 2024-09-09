package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.payment.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.application.port.TossOperator;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final TossOperator tossOperator;

    @Override
    public TossPaymentDetails getPaymentInfoByOrderId(String orderId) {
        return this.findPaymentInfoByOrderId(orderId)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.USER_NOT_FOUND));
    }

    @Override
    public Optional<TossPaymentDetails> findPaymentInfoByOrderId(String orderId) {
        try {
            TossPaymentDetails tossPaymentResponse = tossOperator.findPaymentInfoByOrderId(
                    orderId
            );

            return Optional.ofNullable(tossPaymentResponse);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new HttpClientErrorException(e.getStatusCode(), e.getMessage());
        }
    }

    @Override
    public TossPaymentDetails confirmPayment(TossConfirmRequest tossConfirmRequest, String idempotencyKey) {
        return tossOperator.confirmPayment(tossConfirmRequest, idempotencyKey);
    }

    @Override
    public TossPaymentDetails cancelPayment(
            TossCancelRequest tossCancelRequest,
            String idempotencyKey
    ) {
        return tossOperator.cancelPayment(
                tossCancelRequest,
                idempotencyKey
        );
    }
}
