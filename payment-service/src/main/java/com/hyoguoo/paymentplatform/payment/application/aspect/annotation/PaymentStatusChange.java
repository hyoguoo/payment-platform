package com.hyoguoo.paymentplatform.payment.application.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 결제 상태 전이 마커 어노테이션 — payment_history audit trail 자동 기록 트리거.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 * aspect 구현체(DomainEventLoggingAspect) 는 infrastructure 에 그대로 둔다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaymentStatusChange {

    String toStatus();

    /**
     * 전이 주체(trigger). 한 메서드가 단일 흐름에서만 불리면 여기에 고정값을 채운다.
     * 여러 흐름에서 불리는 메서드는 비워두고, 대신 파라미터에
     * {@code com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Trigger} 를 붙여
     * 호출자가 값을 넘기게 한다 — 파라미터 값이 있으면 이 기본값보다 우선한다.
     */
    String trigger() default "";
}
