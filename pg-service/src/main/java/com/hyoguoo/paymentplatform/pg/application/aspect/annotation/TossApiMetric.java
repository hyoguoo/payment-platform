package com.hyoguoo.paymentplatform.pg.application.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * pg-service 자체 소유 복제본. ADR-30 §2-6 — 공통 library jar 금지, 서비스 소유.
 * payment-service 의존 없음.
 *
 * <p>K9d: infrastructure.aspect.annotation → application.aspect.annotation 으로 이동.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약 준수.
 * aspect 구현체(TossApiMetricsAspect)는 infrastructure 에 그대로 둔다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TossApiMetric {

    Type value();

    String operation() default "confirm";

    enum Type {
        SUCCESS,
        RETRYABLE_FAILURE,
        NON_RETRYABLE_FAILURE
    }
}
