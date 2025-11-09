package com.hyoguoo.paymentplatform.core.common.metrics.aspect;

import com.hyoguoo.paymentplatform.core.common.metrics.PaymentTransitionMetrics;
import com.hyoguoo.paymentplatform.core.common.metrics.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.Duration;
import java.time.LocalDateTime;
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

    private final PaymentTransitionMetrics paymentTransitionMetrics;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Around("@annotation(paymentStatusChange)")
    public Object recordStatusChange(
            ProceedingJoinPoint joinPoint,
            PaymentStatusChange paymentStatusChange
    ) throws Throwable {
        PaymentEvent originalEvent = extractPaymentEvent(joinPoint);
        String fromStatus = originalEvent != null ? originalEvent.getStatus().name() : "UNKNOWN";
        LocalDateTime lastStatusChangedAt = originalEvent != null ? originalEvent.getLastStatusChangedAt() : null;

        Object result = joinPoint.proceed();

        PaymentEvent resultEvent = (result instanceof PaymentEvent paymentEvent) ? paymentEvent : null;
        String toStatus = resultEvent != null ? resultEvent.getStatus().name() : paymentStatusChange.toStatus();

        String trigger = paymentStatusChange.trigger();
        if ("auto".equals(trigger)) {
            trigger = detectTriggerFromCallStack();
        }

        Duration duration = null;
        if (lastStatusChangedAt != null) {
            LocalDateTime now = localDateTimeProvider.now();
            duration = Duration.between(lastStatusChangedAt, now);
        }

        // Record transition metric with duration
        paymentTransitionMetrics.recordTransition(
                fromStatus,
                toStatus,
                trigger,
                duration
        );

        return result;
    }

    private PaymentEvent extractPaymentEvent(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof PaymentEvent paymentevent) {
                return paymentevent;
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
