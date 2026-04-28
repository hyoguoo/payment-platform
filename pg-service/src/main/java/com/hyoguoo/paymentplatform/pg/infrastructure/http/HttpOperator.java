package com.hyoguoo.paymentplatform.pg.infrastructure.http;

import java.util.Map;

/**
 * pg-service 내부 HTTP 호출 포트.
 * 공통 jar 금지 정책에 따라 pg-service 독립 복제본으로 보유한다.
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
