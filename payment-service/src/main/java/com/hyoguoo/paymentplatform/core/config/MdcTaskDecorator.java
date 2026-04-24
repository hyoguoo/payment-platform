package com.hyoguoo.paymentplatform.core.config;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * MDC 컨텍스트를 Runnable 실행 스레드로 전파하는 TaskDecorator.
 *
 * <p>@Async("outboxRelayExecutor") 경계에서 호출 스레드의 MDC(traceId 등)가
 * VT 로 승계되도록 보장한다.
 * 실행 완료 또는 예외 발생 후에는 반드시 MDC 를 clear 하여 스레드 오염을 방지한다.
 *
 * <p>사용: {@link AsyncConfig#outboxRelayExecutor()} 의 TaskExecutorAdapter 에 적용.
 *
 * @see AsyncConfig
 * @see org.springframework.core.task.support.ContextPropagatingTaskDecorator Spring 6.1+ 내장 대안
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
