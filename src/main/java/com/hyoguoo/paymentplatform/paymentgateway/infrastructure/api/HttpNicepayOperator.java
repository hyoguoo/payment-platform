package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.NicepayOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.NicepayPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.NicepayPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.NicepayPaymentApiResponse;
import java.net.SocketTimeoutException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@Component
@RequiredArgsConstructor
public class HttpNicepayOperator implements NicepayOperator {

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";
    private static final String NETWORK_ERROR_CODE = "NETWORK_ERROR";
    private static final String NETWORK_ERROR_MESSAGE = "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
    private static final String UNAUTHORIZED_CODE = "UNAUTHORIZED_KEY";
    private static final String UNAUTHORIZED_MESSAGE = "인증되지 않은 클라이언트 키 혹은 시크릿 키입니다.";

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;

    @Value("${spring.myapp.nicepay.client-key}")
    private String clientKey;

    @Value("${spring.myapp.nicepay.secret-key}")
    private String secretKey;

    @Value("${spring.myapp.nicepay.api-url}")
    private String nicepayApiUrl;

    @Override
    public NicepayPaymentInfo confirmPayment(NicepayConfirmCommand nicepayConfirmCommand)
            throws PaymentGatewayApiException {
        try {
            Map<String, String> httpHeaderMap = Map.of(
                    AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
            );

            NicepayPaymentApiResponse response = httpOperator.requestPost(
                    nicepayApiUrl + "/v1/payments/" + nicepayConfirmCommand.getTid(),
                    httpHeaderMap,
                    Map.of("amount", nicepayConfirmCommand.getAmount()),
                    NicepayPaymentApiResponse.class
            );

            return toNicepayPaymentInfo(response);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw PaymentGatewayApiException.of(UNAUTHORIZED_CODE, UNAUTHORIZED_MESSAGE);
            }
            NicepayPaymentApiFailResponse failResponse = parseErrorResponse(e.getMessage());
            throw PaymentGatewayApiException.of(
                    failResponse.getResultCode(),
                    failResponse.getResultMsg()
            );
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw PaymentGatewayApiException.of(NETWORK_ERROR_CODE, NETWORK_ERROR_MESSAGE);
            }
            throw e;
        }
    }

    @Override
    public NicepayPaymentInfo getPaymentInfoByTid(String tid) {
        Map<String, String> httpHeaderMap = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
        );

        NicepayPaymentApiResponse response = httpOperator.requestGet(
                nicepayApiUrl + "/v1/payments/" + tid,
                httpHeaderMap,
                NicepayPaymentApiResponse.class
        );

        return toNicepayPaymentInfo(response);
    }

    @Override
    public NicepayPaymentInfo getPaymentInfoByOrderId(String orderId) throws PaymentGatewayApiException {
        try {
            Map<String, String> httpHeaderMap = Map.of(
                    AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
            );

            NicepayPaymentApiResponse response = httpOperator.requestGet(
                    nicepayApiUrl + "/v1/payments/find/" + orderId,
                    httpHeaderMap,
                    NicepayPaymentApiResponse.class
            );

            return toNicepayPaymentInfo(response);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw PaymentGatewayApiException.of(UNAUTHORIZED_CODE, UNAUTHORIZED_MESSAGE);
            }
            NicepayPaymentApiFailResponse failResponse = parseErrorResponse(e.getMessage());
            throw PaymentGatewayApiException.of(
                    failResponse.getResultCode(),
                    failResponse.getResultMsg()
            );
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw PaymentGatewayApiException.of(NETWORK_ERROR_CODE, NETWORK_ERROR_MESSAGE);
            }
            throw e;
        }
    }

    @Override
    public NicepayPaymentInfo cancelPayment(NicepayCancelCommand nicepayCancelCommand) {
        Map<String, String> httpHeaderMap = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
        );

        NicepayPaymentApiResponse response = httpOperator.requestPost(
                nicepayApiUrl + "/v1/payments/" + nicepayCancelCommand.getTid() + "/cancel",
                httpHeaderMap,
                Map.of(
                        "reason", nicepayCancelCommand.getReason(),
                        "orderId", nicepayCancelCommand.getOrderId()
                ),
                NicepayPaymentApiResponse.class
        );

        return toNicepayPaymentInfo(response);
    }

    private String generateBasicAuthHeaderValue() {
        return BASIC_AUTHORIZATION_TYPE + encodeUtils.encodeBase64(clientKey + ":" + secretKey);
    }

    private NicepayPaymentInfo toNicepayPaymentInfo(NicepayPaymentApiResponse response) {
        return NicepayPaymentInfo.builder()
                .tid(response.getTid())
                .orderId(response.getOrderId())
                .amount(response.getAmount())
                .status(response.getStatus())
                .resultCode(response.getResultCode())
                .resultMsg(response.getResultMsg())
                .paidAt(response.getPaidAt())
                .build();
    }

    // TODO: 파싱 방법 개선 필요 (Toss와 동일한 패턴)
    private NicepayPaymentApiFailResponse parseErrorResponse(String errorResponse) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return objectMapper.readValue(
                    extractJsonPart(errorResponse),
                    NicepayPaymentApiFailResponse.class
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to parse NicePay error response: " + errorResponse
            );
        }
    }

    private String extractJsonPart(String errorResponse) {
        int start = errorResponse.indexOf('{');
        int end = errorResponse.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return errorResponse.substring(start, end + 1);
        }
        return errorResponse;
    }
}
