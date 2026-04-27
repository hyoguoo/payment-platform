package com.hyoguoo.paymentplatform.pg.infrastructure.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Base64 인코딩 유틸. Toss/NicePay Basic 인증 헤더 생성에 사용.
 * 공통 jar 금지 정책에 따라 pg-service 독립 복제본으로 보유한다.
 */
@Component
public class EncodeUtils {

    public String encodeBase64(String src) {
        return Base64.getEncoder().encodeToString(src.getBytes(StandardCharsets.UTF_8));
    }
}
