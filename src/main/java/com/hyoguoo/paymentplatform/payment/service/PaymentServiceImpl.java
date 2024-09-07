package com.hyoguoo.paymentplatform.payment.service;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.HttpClientErrorException;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;

@Service
@Validated
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    @Value("${spring.myapp.toss-payments.secret-key}")
    private String secretKey;
    @Value("${spring.myapp.toss-payments.api-url}")
    private String tossApiUrl;

    @Override
    public TossPaymentResponse getPaymentInfoByOrderId(String orderId) {
        return findPaymentInfoByOrderId(orderId)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.USER_NOT_FOUND));
    }

    @Override
    public Optional<TossPaymentResponse> findPaymentInfoByOrderId(String orderId) {
        try {
            TossPaymentResponse tossPaymentResponse = httpOperator.requestGetWithBasicAuthorization(
                    tossApiUrl + "/orders/" + orderId,
                    encodeUtils.encodeBase64(secretKey + ":"),
                    TossPaymentResponse.class
            );

            return Optional.ofNullable(tossPaymentResponse);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new HttpClientErrorException(e.getStatusCode(), e.getMessage());
        }
    }

    @Override
    public TossPaymentResponse confirmPayment(
            @Valid TossConfirmRequest tossConfirmRequest,
            String idempotencyKey
    ) {
        return httpOperator.requestPostWithBasicAuthorization(
                tossApiUrl + "/confirm",
                encodeUtils.encodeBase64(secretKey + ":"),
                idempotencyKey,
                tossConfirmRequest,
                TossPaymentResponse.class
        );
    }

    @Override
    public TossPaymentResponse cancelPayment(
            String paymentKey,
            String idempotencyKey,
            @Valid TossCancelRequest tossCancelRequest
    ) {
        return httpOperator.requestPostWithBasicAuthorization(
                tossApiUrl + "/" + paymentKey + "/cancel",
                encodeUtils.encodeBase64(secretKey + ":"),
                idempotencyKey,
                tossCancelRequest,
                TossPaymentResponse.class
        );
    }
}
