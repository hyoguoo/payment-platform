package com.hyoguoo.paymentplatform.pg.application.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * pg-service 자체 소유 복제본 — 공통 library jar 를 두지 않고 각 서비스가 같은 어노테이션을 들고 있는다.
 * {@link TossApiMetric} AOP 메서드 파라미터 중 에러 코드 문자열을 식별하는 마커 어노테이션이며,
 * 파라미터 타입은 {@link String} 이어야 한다.
 *
 * <p>application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 * aspect 구현체(TossApiMetricsAspect) 는 infrastructure 에 그대로 둔다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ErrorCode {

}
