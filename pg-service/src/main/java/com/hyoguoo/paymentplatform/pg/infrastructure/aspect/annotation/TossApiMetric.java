package com.hyoguoo.paymentplatform.pg.infrastructure.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * pg-service 자체 소유 복제본. ADR-30 §2-6 — 공통 library jar 금지, 서비스 소유.
 * payment-service 의존 없음.
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
