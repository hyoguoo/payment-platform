package com.hyoguoo.paymentplatform.pg.infrastructure.http;

import java.util.Map;

/**
 * pg-service 내부 HTTP 호출 포트.
 * ADR-30: payment-service 의존 없이 pg-service 독립 복제.
 */
public interface HttpOperator {

    <T> T requestGet(
            String url,
            Map<String, String> httpHeaderMap,
            Class<T> responseType
    );

    <T, E> E requestPost(
            String url,
            Map<String, String> httpHeaderMap,
            T body,
            Class<E> responseType
    );
}
