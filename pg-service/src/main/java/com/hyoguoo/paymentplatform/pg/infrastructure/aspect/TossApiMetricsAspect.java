package com.hyoguoo.paymentplatform.pg.infrastructure.aspect;

import com.hyoguoo.paymentplatform.pg.application.aspect.annotation.ErrorCode;
import com.hyoguoo.paymentplatform.pg.application.aspect.annotation.TossApiMetric;
import java.lang.reflect.Parameter;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * pg-service 자체 소유 복제본. ADR-30 §2-6 — 공통 library jar 금지, 서비스 소유.
 * payment-service 의존 없음.
 *
 * <p>pointcut 대상: {@code com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.*}
 * (T2b-01에서 실제 HTTP 호출 구현 시 @TossApiMetric 어노테이션 적용 예정)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TossApiMetricsAspect {

    private final TossApiMetrics tossApiMetrics;

    @Around("@annotation(tossApiMetric)")
    public Object recordTossApiMetric(
            ProceedingJoinPoint joinPoint,
            TossApiMetric tossApiMetric
    ) throws Throwable {
        long startTime = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;

            handleSuccess(joinPoint, tossApiMetric, duration);

            return result;
        } catch (Throwable e) {
            // Error(OOM 등)도 메트릭 기록 후 re-throw — catch(Exception) 보다 넓게 잡는다.
            long duration = System.currentTimeMillis() - startTime;

            handleFailure(tossApiMetric, duration);

            throw e;
        }
    }

    private void handleSuccess(ProceedingJoinPoint joinPoint, TossApiMetric tossApiMetric, long duration) {
        String operation = tossApiMetric.operation();

        switch (tossApiMetric.value()) {
            case SUCCESS:
                tossApiMetrics.recordTossApiCall(operation, duration, true);
                break;
            case RETRYABLE_FAILURE, NON_RETRYABLE_FAILURE:
                extractErrorCode(joinPoint).ifPresent(
                        errorCode -> tossApiMetrics.recordTossApiCall(operation, duration, false, errorCode)
                );
                break;
        }
    }

    private void handleFailure(TossApiMetric tossApiMetric, long duration) {
        String operation = tossApiMetric.operation();

        switch (tossApiMetric.value()) {
            case SUCCESS:
                tossApiMetrics.recordTossApiCall(operation, duration, false);
                break;
            case RETRYABLE_FAILURE, NON_RETRYABLE_FAILURE:
                break;
        }
    }

    private Optional<String> extractErrorCode(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Parameter[] parameters = signature.getMethod().getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(ErrorCode.class) && args[i] instanceof String errorCode) {
                return Optional.of(errorCode);
            }
        }

        return Optional.empty();
    }
}
