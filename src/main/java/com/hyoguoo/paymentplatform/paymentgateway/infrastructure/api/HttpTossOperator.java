package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.PaymentGatewayInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        return PaymentGatewayInfrastructureMapper.toSuccessTossPaymentInfo(tossPaymentApiResponse);
    }

    @Override
    public TossPaymentInfo confirmPayment(
            TossConfirmCommand tossConfirmCommand,
            String idempotencyKey
    ) {
        try {
            TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestPostWithBasicAuthorization(
                    tossApiUrl + "/confirm",
                    encodeUtils.encodeBase64(secretKey + ":"),
                    idempotencyKey,
                    tossConfirmCommand,
                    TossPaymentApiResponse.class
            );

            return PaymentGatewayInfrastructureMapper.toSuccessTossPaymentInfo(tossPaymentApiResponse);
        } catch (Exception e) {
            TossPaymentApiFailResponse tossPaymentApiFailResponse = parseErrorResponse(
                    e.getMessage());
            return PaymentGatewayInfrastructureMapper.toFailureTossPaymentInfo(tossPaymentApiFailResponse);
        }
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

        return PaymentGatewayInfrastructureMapper.toSuccessTossPaymentInfo(tossPaymentApiResponse);
    }

    // TODO: 파싱 방법 개선 필요
    private TossPaymentApiFailResponse parseErrorResponse(String errorResponse) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String jsonPart = extractJsonPart(errorResponse);

            return objectMapper.readValue(jsonPart, TossPaymentApiFailResponse.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse error response: " + errorResponse);
        }
    }

    private String extractJsonPart(String errorResponse) {
        Pattern pattern = Pattern.compile("\\{.*\\}");
        Matcher matcher = pattern.matcher(errorResponse);

        if (matcher.find()) {
            return matcher.group();
        }

        return errorResponse;
    }
}
