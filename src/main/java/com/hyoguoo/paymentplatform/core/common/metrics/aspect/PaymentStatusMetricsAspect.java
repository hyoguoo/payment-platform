package com.hyoguoo.paymentplatform.core.common.metrics.aspect;

import com.hyoguoo.paymentplatform.core.common.metrics.PaymentStatusMetrics;
import com.hyoguoo.paymentplatform.core.common.metrics.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PaymentStatusMetricsAspect {

    private final PaymentStatusMetrics paymentStatusMetrics;

    @Around("@annotation(paymentStatusChange)")
    public Object recordStatusChange(
            ProceedingJoinPoint joinPoint,
            PaymentStatusChange paymentStatusChange
    ) throws Throwable {
        // Get original PaymentEvent from method arguments
        PaymentEvent originalEvent = extractPaymentEvent(joinPoint);
        String fromStatus = originalEvent != null ? originalEvent.getStatus().name() : "UNKNOWN";

        // Execute the method
        Object result = joinPoint.proceed();

        // Get result PaymentEvent
        PaymentEvent resultEvent = (result instanceof PaymentEvent) ? (PaymentEvent) result : null;
        String toStatus = resultEvent != null ? resultEvent.getStatus().name() : paymentStatusChange.toStatus();

        // Determine trigger
        String trigger = paymentStatusChange.trigger();
        if ("auto".equals(trigger)) {
            trigger = detectTriggerFromCallStack();
        }

        // Record metric
        paymentStatusMetrics.recordStatusChange(
                fromStatus,
                toStatus,
                trigger
        );

        return result;
    }

    private PaymentEvent extractPaymentEvent(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof PaymentEvent) {
                return (PaymentEvent) arg;
            }
        }
        return null;
    }

    private String detectTriggerFromCallStack() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();

            if (className.contains("PaymentConfirmService")) {
                return "confirm";
            } else if (className.contains("PaymentRecoverService")) {
                return "recovery";
            } else if (className.contains("PaymentExpirationService")) {
                return "expiration";
            }
        }

        return "unknown";
    }
}
