package com.hyoguoo.paymentplatform.payment.presentation;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class NicepayReturnController {

    /**
     * NicePay JS SDK의 returnUrl 수신 엔드포인트.
     * NicePay는 인증 완료 후 이 URL로 form POST를 보낸다.
     * tid 를 paymentKey 로 매핑하여 success.html 로 리다이렉트한다.
     */
    @PostMapping("/payment/nicepay-return")
    public String receiveNicepayReturn(
            @RequestParam(value = "tid", required = false) String tid,
            @RequestParam(value = "orderId", required = false) String orderId,
            @RequestParam(value = "amount", required = false) BigDecimal amount,
            @RequestParam(value = "authResultCode", required = false) String authResultCode,
            @RequestParam(value = "authResultMsg", required = false) String authResultMsg
    ) {
        if (!"0000".equals(authResultCode)) {
            String code = authResultCode != null ? authResultCode : "NICEPAY_ERROR";
            String message = authResultMsg != null ? authResultMsg : "결제 인증에 실패했습니다.";
            return "redirect:/payment/fail.html"
                    + "?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&message=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
        }

        // NOTE: paymentKey가 URL에 노출됨 — Toss와 동일 패턴, 향후 세션 기반 전달 고려
        return "redirect:/payment/success.html"
                + "?paymentKey=" + URLEncoder.encode(tid != null ? tid : "", StandardCharsets.UTF_8)
                + "&orderId=" + URLEncoder.encode(orderId != null ? orderId : "", StandardCharsets.UTF_8)
                + "&amount=" + amount
                + "&gatewayType=NICEPAY";
    }
}
