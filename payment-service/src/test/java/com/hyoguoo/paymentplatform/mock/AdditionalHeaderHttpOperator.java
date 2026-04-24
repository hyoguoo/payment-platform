package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperatorImpl;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

public class AdditionalHeaderHttpOperator extends HttpOperatorImpl {

    private final Map<String, String> additionalHeaders;

    /**
     * T-E2: WebClient.Builder 생성자 주입 구조에 맞춰 갱신.
     * 테스트용 기본값(timeout 3000ms/5000ms, noop builder)으로 초기화.
     */
    public AdditionalHeaderHttpOperator() {
        super(WebClient.builder(), 3000, 5000);
        this.additionalHeaders = new HashMap<>();
    }

    // 리플렉션을 통해 호출되는 메서드
    @SuppressWarnings("unused")
    public void addHeader(String key, String value) {
        additionalHeaders.put(key, value);
    }

    @Override
    protected Map<String, String> getAdditionalHeaders() {
        return additionalHeaders;
    }
}
