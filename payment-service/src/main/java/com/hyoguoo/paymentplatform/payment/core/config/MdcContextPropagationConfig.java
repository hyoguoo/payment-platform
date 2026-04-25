package com.hyoguoo.paymentplatform.payment.core.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer Context Propagation — Slf4j MDC ThreadLocalAccessor 등록.
 *
 * <p>T-E1: {@code ContextRegistry.getInstance()} 에 {@link Slf4jMdcThreadLocalAccessor} 를
 * 등록하여 {@code ContextExecutorService.wrap()} 경유 VT 실행 시 MDC(traceId 등) 가 승계되도록 한다.
 *
 * <p>등록은 애플리케이션 기동 직후 1회만 수행된다.
 * Spring Boot auto-configuration 이 동일 accessor 를 등록하지 않는 경우에 대비한 명시 등록.
 */
@Configuration
public class MdcContextPropagationConfig {

    @PostConstruct
    public void registerMdcAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jMdcThreadLocalAccessor());
    }
}
