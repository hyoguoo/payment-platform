package com.hyoguoo.paymentplatform.core.common.metrics.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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
