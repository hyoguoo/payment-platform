package com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 상태 전이 지표의 trigger(전이 주체) 값을 호출자가 넘기는 파라미터 표식.
 * 한 메서드가 여러 흐름에서 호출되어 고정 애노테이션 값으로 표현할 수 없을 때,
 * 이 표식이 붙은 파라미터 값을 호출자가 직접 채운다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Trigger {

}
