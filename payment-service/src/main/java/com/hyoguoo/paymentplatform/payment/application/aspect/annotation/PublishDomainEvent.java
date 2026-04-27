package com.hyoguoo.paymentplatform.payment.application.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 도메인 이벤트 발행 마커 어노테이션.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 * aspect 구현체(DomainEventLoggingAspect) 는 infrastructure 에 그대로 둔다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublishDomainEvent {

    String action() default "";
}
