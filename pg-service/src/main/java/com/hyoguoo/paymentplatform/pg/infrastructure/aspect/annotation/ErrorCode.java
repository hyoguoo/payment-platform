package com.hyoguoo.paymentplatform.pg.infrastructure.aspect.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * pg-service 자체 소유 복제본. ADR-30 §2-6 — 공통 library jar 금지, 서비스 소유.
 * {@link TossApiMetric} AOP 메서드 파라미터 중 에러 코드 문자열을 식별하는 마커 어노테이션.
 * 파라미터 타입은 {@link String}이어야 한다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ErrorCode {

}
