package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperatorImpl;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class AdditionalHeaderHttpOperator extends HttpOperatorImpl {

    private final Map<String, String> additionalHeaders;

    public AdditionalHeaderHttpOperator() {
        this.additionalHeaders = new HashMap<>();
    }

    // 리플렉션을 통해 호출되는 메서드
    @SuppressWarnings("unused")
    public void addHeader(String key, String value) {
        additionalHeaders.put(key, value);
    }

    // 헤더 추가를 위한 메서드
    @Override
    protected HttpHeaders generateHttpHeaders(Map<String, String> httpHeaders) {
        HttpHeaders headers = super.generateHttpHeaders(httpHeaders);
        headers.setAll(additionalHeaders);
        return headers;
    }
}
