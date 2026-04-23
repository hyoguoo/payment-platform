package com.hyoguoo.paymentplatform.pg.infrastructure.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Base64 인코딩 유틸. Toss/NicePay Basic 인증 헤더 생성에 사용.
 * ADR-30: payment-service 의존 없이 pg-service 독립 복제.
 */
@Component
public class EncodeUtils {

    public String encodeBase64(String src) {
        return Base64.getEncoder().encodeToString(src.getBytes(StandardCharsets.UTF_8));
    }
}
