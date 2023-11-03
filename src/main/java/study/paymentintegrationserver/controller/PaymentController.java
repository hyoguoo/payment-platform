package study.paymentintegrationserver.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.paymentintegrationserver.domain.TossPayments;
import study.paymentintegrationserver.dto.payment.PaymentCofirmResponse;
import study.paymentintegrationserver.dto.payment.PaymentConfirmRequest;
import study.paymentintegrationserver.dto.payment.PaymentCreateRequest;
import study.paymentintegrationserver.dto.payment.PaymentCreateResponse;
import study.paymentintegrationserver.util.EncodeUtils;
import study.paymentintegrationserver.util.HttpUtils;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/payments")
// TODO: service를 통해 비즈니스 로직을 분리
public class PaymentController {

    @Value("${spring.myapp.toss-payments.secret-key}")
    private String secretKey;
    @Value("${spring.myapp.toss-payments.api-url}")
    private String tossApiUrl;

    @PostMapping("/create")
    public PaymentCreateResponse requestPayment(@RequestBody PaymentCreateRequest paymentCreateRequest) {
        return new PaymentCreateResponse(paymentCreateRequest.getOrderId());
    }

    @PostMapping("/confirm")
    public PaymentCofirmResponse getPaymentPage(@RequestBody PaymentConfirmRequest paymentConfirmRequest) {
        TossPayments tossPayments = HttpUtils.requestPostWithBasicAuthorization(
                tossApiUrl + "/confirm",
                EncodeUtils.encodeBase64(secretKey + ":"),
                paymentConfirmRequest,
                TossPayments.class);

        return new PaymentCofirmResponse(tossPayments.getOrderId(), BigDecimal.valueOf(tossPayments.getTotalAmount()));
    }
}
