package com.hyoguoo.paymentplatform.pg.infrastructure.config;

import io.micrometer.context.ThreadLocalAccessor;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Micrometer Context Propagation — pg-service Slf4j MDC 전파를 위한 ThreadLocalAccessor.
 *
 * <p>{@code ContextRegistry.getInstance().registerThreadLocalAccessor(this)} 로 등록하면
 * {@code ContextExecutorService.wrap()} 이 VT submit 시 MDC(traceId 등) 스냅샷을 자동으로 캡처·복원한다.
 *
 * <p>등록 시점: {@link PgServiceConfig} @PostConstruct 로 애플리케이션 기동 직후 등록.
 *
 * @see PgServiceConfig
 */
public class PgSlf4jMdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    static final String KEY = "pg.slf4j.mdc";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public Map<String, String> getValue() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public void setValue(Map<String, String> value) {
        MDC.setContextMap(value);
    }

    @Override
    public void setValue() {
        MDC.clear();
    }

    @Override
    public void restore(Map<String, String> previousValue) {
        if (previousValue != null) {
            MDC.setContextMap(previousValue);
        } else {
            MDC.clear();
        }
    }
}
