package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.api;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.PaymentGatewayInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse;
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
    public TossPaymentInfo findPaymentInfoByOrderId(String orderId) {
        TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestGetWithBasicAuthorization(
                tossApiUrl + "/orders/" + orderId,
                encodeUtils.encodeBase64(secretKey + ":"),
                TossPaymentApiResponse.class
        );

        return PaymentGatewayInfrastructureMapper.toTossPaymentInfo(tossPaymentApiResponse);
    }

    @Override
    public TossPaymentInfo confirmPayment(
            TossConfirmCommand tossConfirmCommand,
            String idempotencyKey
    ) {
        TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestPostWithBasicAuthorization(
                tossApiUrl + "/confirm",
                encodeUtils.encodeBase64(secretKey + ":"),
                idempotencyKey,
                tossConfirmCommand,
                TossPaymentApiResponse.class
        );

        return PaymentGatewayInfrastructureMapper.toTossPaymentInfo(tossPaymentApiResponse);
    }

    @Override
    public TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    ) {
        TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestPostWithBasicAuthorization(
                tossApiUrl + "/" + tossCancelCommand.getPaymentKey() + "/cancel",
                encodeUtils.encodeBase64(secretKey + ":"),
                idempotencyKey,
                tossCancelCommand,
                TossPaymentApiResponse.class
        );

        return PaymentGatewayInfrastructureMapper.toTossPaymentInfo(tossPaymentApiResponse);
    }
}
