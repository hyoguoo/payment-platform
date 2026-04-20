package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import java.util.Map;

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
