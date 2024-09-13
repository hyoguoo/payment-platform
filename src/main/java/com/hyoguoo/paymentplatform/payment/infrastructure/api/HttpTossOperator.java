package com.hyoguoo.paymentplatform.payment.infrastructure.api;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.payment.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.application.port.TossOperator;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.payment.infrastructure.dto.response.TossPaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpTossOperator implements TossOperator {

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    @Value("${spring.myapp.toss-payments.secret-key}")
    private String secretKey;
    @Value("${spring.myapp.toss-payments.api-url}")
    private String tossApiUrl;

    @Override
    public TossPaymentDetails findPaymentInfoByOrderId(String orderId) {
        TossPaymentResponse tossPaymentResponse = httpOperator.requestGetWithBasicAuthorization(
                tossApiUrl + "/orders/" + orderId,
                encodeUtils.encodeBase64(secretKey + ":"),
                TossPaymentResponse.class
        );

        return PaymentInfrastructureMapper.toPaymentDetails(tossPaymentResponse);
    }

    @Override
    public TossPaymentDetails confirmPayment(
            TossConfirmRequest tossConfirmRequest,
            String idempotencyKey
    ) {
        TossPaymentResponse tossPaymentResponse = httpOperator.requestPostWithBasicAuthorization(
                tossApiUrl + "/confirm",
                encodeUtils.encodeBase64(secretKey + ":"),
                idempotencyKey,
                tossConfirmRequest,
                TossPaymentResponse.class
        );

        return PaymentInfrastructureMapper.toPaymentDetails(tossPaymentResponse);
    }

    @Override
    public TossPaymentDetails cancelPayment(
            TossCancelRequest tossCancelRequest,
            String idempotencyKey
    ) {
        TossPaymentResponse tossPaymentResponse = httpOperator.requestPostWithBasicAuthorization(
                tossApiUrl + "/" + tossCancelRequest.getPaymentKey() + "/cancel",
                encodeUtils.encodeBase64(secretKey + ":"),
                idempotencyKey,
                tossCancelRequest,
                TossPaymentResponse.class
        );

        return PaymentInfrastructureMapper.toPaymentDetails(tossPaymentResponse);
    }
}
