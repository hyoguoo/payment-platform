package study.paymentintegrationserver.service;

import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.exception.PaymentErrorMessage;
import study.paymentintegrationserver.exception.PaymentException;
import study.paymentintegrationserver.util.EncodeUtils;
import study.paymentintegrationserver.util.HttpUtils;

@Service
@Validated
public class PaymentService {

    @Value("${spring.myapp.toss-payments.secret-key}")
    private String secretKey;
    @Value("${spring.myapp.toss-payments.api-url}")
    private String tossApiUrl;

    public TossPaymentResponse getPaymentInfoByOrderId(String orderId) {
        return findPaymentInfoByOrderId(orderId)
                .orElseThrow(() -> PaymentException.of(PaymentErrorMessage.NOT_FOUND));
    }

    public Optional<TossPaymentResponse> findPaymentInfoByOrderId(String orderId) {
        return HttpUtils.requestGetWithBasicAuthorization(
                tossApiUrl + "/orders/" + orderId,
                EncodeUtils.encodeBase64(secretKey + ":"),
                TossPaymentResponse.class
        );
    }

    public TossPaymentResponse confirmPayment(@Valid TossConfirmRequest tossConfirmRequest) {
        return HttpUtils.requestPostWithBasicAuthorization(
                tossApiUrl + "/confirm",
                EncodeUtils.encodeBase64(secretKey + ":"),
                tossConfirmRequest,
                TossPaymentResponse.class
        );
    }

    public TossPaymentResponse cancelPayment(
            String paymentKey,
            @Valid TossCancelRequest tossCancelRequest
    ) {
        return HttpUtils.requestPostWithBasicAuthorization(
                tossApiUrl + "/" + paymentKey + "/cancel",
                EncodeUtils.encodeBase64(secretKey + ":"),
                tossCancelRequest,
                TossPaymentResponse.class
        );
    }
}
