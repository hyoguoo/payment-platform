package com.hyoguoo.paymentplatform.payment.core.config;

import io.micrometer.context.ThreadLocalAccessor;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Micrometer Context Propagation — Slf4j MDC 전파를 위한 ThreadLocalAccessor.
 *
 * <p>{@code ContextRegistry.getInstance().registerThreadLocalAccessor(this)} 로 등록해 두면
 * {@code ContextExecutorService.wrap()} 이 VT submit 시 MDC 스냅샷을 자동으로 캡처·복원한다.
 *
 * <p>등록 시점: {@link MdcContextPropagationConfig} @PostConstruct 로 애플리케이션 기동 직후 등록.
 *
 * @see MdcContextPropagationConfig
 */
public class Slf4jMdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    static final String KEY = "slf4j.mdc";

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
