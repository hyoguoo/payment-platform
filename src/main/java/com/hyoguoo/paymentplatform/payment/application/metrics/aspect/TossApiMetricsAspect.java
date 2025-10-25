package com.hyoguoo.paymentplatform.payment.application.metrics.aspect;

import com.hyoguoo.paymentplatform.payment.application.metrics.TossApiMetrics;
import com.hyoguoo.paymentplatform.payment.application.metrics.annotation.ErrorCode;
import com.hyoguoo.paymentplatform.payment.application.metrics.annotation.TossApiMetric;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import java.lang.reflect.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

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
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            handleFailure(tossApiMetric, duration, e);

            throw e;
        }
    }

    private void handleSuccess(ProceedingJoinPoint joinPoint, TossApiMetric tossApiMetric, long duration) {
        String operation = tossApiMetric.operation();

        switch (tossApiMetric.value()) {
            case SUCCESS:
                tossApiMetrics.recordTossApiCall(operation, duration, true);
                break;
            case RETRYABLE_FAILURE:
            case NON_RETRYABLE_FAILURE:
                TossPaymentErrorCode errorCode = extractErrorCode(joinPoint);
                if (errorCode != null) {
                    tossApiMetrics.recordTossApiCall(operation, duration, false, errorCode.name());
                }
                break;
        }
    }

    private void handleFailure(TossApiMetric tossApiMetric, long duration, Exception e) {
        String operation = tossApiMetric.operation();

        switch (tossApiMetric.value()) {
            case SUCCESS:
                tossApiMetrics.recordTossApiCall(operation, duration, false);
                break;
            case RETRYABLE_FAILURE:
            case NON_RETRYABLE_FAILURE:
                break;
        }
    }

    private TossPaymentErrorCode extractErrorCode(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Parameter[] parameters = signature.getMethod().getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(ErrorCode.class)) {
                return (TossPaymentErrorCode) args[i];
            }
        }

        return null;
    }
}
