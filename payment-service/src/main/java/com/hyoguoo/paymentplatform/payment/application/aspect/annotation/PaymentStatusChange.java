package com.hyoguoo.paymentplatform.payment.application.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * K9d: infrastructure.aspect.annotation → application.aspect.annotation 으로 이동.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약 준수.
 * aspect 구현체(DomainEventLoggingAspect)는 infrastructure 에 그대로 둔다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaymentStatusChange {

    String toStatus();

    String trigger();
}
