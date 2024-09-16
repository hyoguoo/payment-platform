package com.hyoguoo.paymentplatform.paymentgateway.application;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayFoundException;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.PaymentGatewayErrorCode;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.port.PaymentGatewayService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
@RequiredArgsConstructor
public class PaymentGatewayServiceImpl implements PaymentGatewayService {

    private final TossOperator tossOperator;

    @Override
    public TossPaymentDetails getPaymentInfoByOrderId(String orderId) {
        return this.findPaymentInfoByOrderId(orderId)
                .orElseThrow(() -> PaymentGatewayFoundException.of(PaymentGatewayErrorCode.USER_NOT_FOUND));
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
