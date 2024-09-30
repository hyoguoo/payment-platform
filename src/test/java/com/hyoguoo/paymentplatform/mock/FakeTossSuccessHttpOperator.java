package com.hyoguoo.paymentplatform.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse.Checkout;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse.Receipt;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class FakeTossSuccessHttpOperator implements HttpOperator {

    public static final String TEST_PAYMENT_KEY = "tviva20240929050058zeWv3";
    public static final double TEST_TOTAL_AMOUNT_1 = 150000.0;
    public static final double TEST_TOTAL_AMOUNT_2 = 60000.0;
    public static final String TEST_ORDER_ID = "55996af6-e5b5-47e5-ac3c-44508ee6fd6b";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private int minDelayMillis;
    private int maxDelayMillis;
    private String code;
    private String message;
    private boolean isErrorInPostRequest;

    @SuppressWarnings("unused")
    public void setDelayRange(int minDelayMillis, int maxDelayMillis) {
        this.minDelayMillis = minDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    @SuppressWarnings("unused")
    public void addErrorInPostRequest(String code, String message) {
        this.code = code;
        this.message = message;
        this.isErrorInPostRequest = true;
    }

    @SuppressWarnings("unused")
    public void clearErrorInPostRequest() {
        this.isErrorInPostRequest = false;
    }

    @SuppressWarnings("java:S2925")
    private void simulateNetworkDelay() {
        long delay = minDelayMillis + (long) (Math.random() * (maxDelayMillis - minDelayMillis));
        try {
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public <T> T requestGet(String url, Map<String, String> httpHeaderMap, Class<T> responseType) {
        simulateNetworkDelay();

        TossPaymentApiResponse tossPaymentApiResponse = TossPaymentApiResponse.builder()
                .version("2022-11-16")
                .paymentKey(TEST_PAYMENT_KEY)
                .type("NORMAL")
                .orderId(TEST_ORDER_ID)
                .orderName("테스트 결제")
                .currency("KRW")
                .totalAmount(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2)
                .balanceAmount(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2)
                .status("IN_PROGRESS")
                .requestedAt("2024-09-29T05:00:58+09:00")
                .useEscrow(false)
                .lastTransactionKey("F9B5D50F006C7027681F716C88FEA07F")
                .suppliedAmount(190909.0)
                .vat(19091.0)
                .cultureExpense(false)
                .taxFreeAmount(0.0)
                .taxExemptionAmount(0)
                .receipt(new Receipt(
                        "https://dashboard.tosspayments.com/receipt/redirection?transactionId=tviva20240929050058zeWv3&ref=PX"))
                .checkout(new Checkout("https://api.tosspayments.com/v1/payments/tviva20240929050058zeWv3/checkout"))
                .country("KR")
                .build();

        return responseType.cast(tossPaymentApiResponse);
    }

    @Override
    public <T, E> E requestPost(String url, Map<String, String> httpHeaderMap, T body, Class<E> responseType) {
        simulateNetworkDelay();

        if (isErrorInPostRequest) {
            TossPaymentApiFailResponse failResponse = TossPaymentApiFailResponse.builder()
                    .code(code)
                    .message(message)
                    .build();

            try {
                String jsonResponse = objectMapper.writeValueAsString(failResponse);

                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.CONTENT_TYPE, "application/json");

                ResponseEntity<String> responseEntity = new ResponseEntity<>(
                        jsonResponse,
                        headers,
                        HttpStatus.BAD_REQUEST
                );

                throw new RuntimeException("Error: " + responseEntity);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("JSON 직렬화 오류", e);
            }
        }

        TossPaymentApiResponse tossPaymentApiResponse = TossPaymentApiResponse.builder()
                .version("2022-11-16")
                .paymentKey(TEST_PAYMENT_KEY)
                .type("NORMAL")
                .orderId(TEST_ORDER_ID)
                .orderName("테스트 결제")
                .currency("KRW")
                .method("카드")
                .totalAmount(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2)
                .balanceAmount(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2)
                .status("DONE")
                .requestedAt("2024-09-29T05:00:58+09:00")
                .approvedAt("2024-09-29T05:03:19+09:00")
                .useEscrow(false)
                .lastTransactionKey("BF89004424EC534BEF612C2EDE128FC0")
                .suppliedAmount(190909.0)
                .vat(19091.0)
                .cultureExpense(false)
                .taxFreeAmount(0.0)
                .taxExemptionAmount(0)
                .receipt(new Receipt(
                        "https://dashboard.tosspayments.com/receipt/redirection?transactionId=tviva20240929050058zeWv3&ref=PX"))
                .checkout(new Checkout("https://api.tosspayments.com/v1/payments/tviva20240929050058zeWv3/checkout"))
                .country("KR")
                .build();

        return responseType.cast(tossPaymentApiResponse);
    }
}
