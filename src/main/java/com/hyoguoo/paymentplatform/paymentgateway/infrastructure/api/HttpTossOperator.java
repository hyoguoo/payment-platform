package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.PaymentGatewayInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@Component
@RequiredArgsConstructor
public class HttpTossOperator implements TossOperator {

    public static final String IDEMPOTENCY_KEY_HEADER_NAME = "Idempotency-Key";
    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";
    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    @Value("${spring.myapp.toss-payments.secret-key}")
    private String secretKey;
    @Value("${spring.myapp.toss-payments.api-url}")
    private String tossApiUrl;

    @Override
    public TossPaymentInfo findPaymentInfoByOrderId(String orderId) {
        Map<String, String> httpHeaderMap = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
        );

        TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestGet(
                tossApiUrl + "/orders/" + orderId,
                httpHeaderMap,
                TossPaymentApiResponse.class
        );

        return PaymentGatewayInfrastructureMapper.toSuccessTossPaymentInfo(tossPaymentApiResponse);
    }

    @Override
    public TossPaymentInfo confirmPayment(
            TossConfirmCommand tossConfirmCommand,
            String idempotencyKey
    ) throws PaymentGatewayApiException {
        try {
            Map<String, String> httpHeaderMap = Map.of(
                    AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue(),
                    IDEMPOTENCY_KEY_HEADER_NAME, idempotencyKey
            );

            TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestPost(
                    tossApiUrl + "/confirm",
                    httpHeaderMap,
                    tossConfirmCommand,
                    TossPaymentApiResponse.class
            );

            return PaymentGatewayInfrastructureMapper.toSuccessTossPaymentInfo(tossPaymentApiResponse);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {

                throw PaymentGatewayApiException.of(
                        TossPaymentErrorCode.UNAUTHORIZED_KEY.name(),
                        TossPaymentErrorCode.UNAUTHORIZED_KEY.getDescription()
                );
            }
            TossPaymentApiFailResponse tossPaymentApiFailResponse = parseErrorResponse(
                    e.getMessage()
            );

            throw PaymentGatewayApiException.of(
                    tossPaymentApiFailResponse.getCode(),
                    tossPaymentApiFailResponse.getMessage()
            );
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw PaymentGatewayApiException.of(
                        TossPaymentErrorCode.NETWORK_ERROR.name(),
                        TossPaymentErrorCode.NETWORK_ERROR.getDescription()
                );
            }
            throw e;
        } catch (Exception e) {
            TossPaymentApiFailResponse tossPaymentApiFailResponse = parseErrorResponse(
                    e.getMessage()
            );

            throw PaymentGatewayApiException.of(
                    tossPaymentApiFailResponse.getCode(),
                    tossPaymentApiFailResponse.getMessage()
            );
        }
    }

    @Override
    public TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    ) {
        Map<String, String> httpHeaderMap = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue(),
                IDEMPOTENCY_KEY_HEADER_NAME, idempotencyKey
        );

        TossPaymentApiResponse tossPaymentApiResponse = httpOperator.requestPost(
                tossApiUrl + "/" + tossCancelCommand.getPaymentKey() + "/cancel",
                httpHeaderMap,
                tossCancelCommand,
                TossPaymentApiResponse.class
        );

        return PaymentGatewayInfrastructureMapper.toSuccessTossPaymentInfo(tossPaymentApiResponse);
    }

    private String generateBasicAuthHeaderValue() {
        return BASIC_AUTHORIZATION_TYPE + encodeUtils.encodeBase64(secretKey + ":");
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
        Pattern pattern = Pattern.compile("\\{.*}");
        Matcher matcher = pattern.matcher(errorResponse);

        if (matcher.find()) {
            return matcher.group();
        }

        return errorResponse;
    }
}
