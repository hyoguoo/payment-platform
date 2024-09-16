package com.hyoguoo.paymentplatform.paymentgateway.application;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
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
    public TossPaymentInfo getPaymentResultByOrderId(String orderId) {
        return this.findPaymentResultByOrderId(orderId)
                .orElseThrow(() -> PaymentGatewayFoundException.of(PaymentGatewayErrorCode.TOSS_PAYMENT_INFO_NOT_FOUND));
    }

    @Override
    public Optional<TossPaymentInfo> findPaymentResultByOrderId(String orderId) {
        try {
            TossPaymentInfo tossPaymentResponse = tossOperator.findPaymentInfoByOrderId(
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
    public TossPaymentInfo confirmPayment(TossConfirmCommand tossConfirmCommand, String idempotencyKey) {
        return tossOperator.confirmPayment(tossConfirmCommand, idempotencyKey);
    }

    @Override
    public TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    ) {
        return tossOperator.cancelPayment(
                tossCancelCommand,
                idempotencyKey
        );
    }
}
