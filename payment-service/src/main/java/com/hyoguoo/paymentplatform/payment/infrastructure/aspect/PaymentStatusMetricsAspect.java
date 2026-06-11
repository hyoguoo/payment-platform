package com.hyoguoo.paymentplatform.payment.infrastructure.aspect;

import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentEventFlowMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentTransitionMetrics;
import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final PaymentEventFlowMetrics paymentEventFlowMetrics;
    private final Clock clock;

    @Around("@annotation(paymentStatusChange)")
    public Object recordStatusChange(
            ProceedingJoinPoint joinPoint,
            PaymentStatusChange paymentStatusChange
    ) throws Throwable {
        PaymentEvent originalEvent = extractPaymentEvent(joinPoint);
        String fromStatus = originalEvent != null ? originalEvent.getStatus().name() : "UNKNOWN";
        Instant lastStatusChangedAt = originalEvent != null ? originalEvent.getLastStatusChangedAt() : null;

        Object result = joinPoint.proceed();

        PaymentEvent resultEvent = (result instanceof PaymentEvent paymentEvent) ? paymentEvent : null;
        String toStatus = resultEvent != null ? resultEvent.getStatus().name() : paymentStatusChange.toStatus();

        String trigger = paymentStatusChange.trigger();
        if ("auto".equals(trigger)) {
            trigger = detectTriggerFromCallStack();
        }

        Duration duration = null;
        if (lastStatusChangedAt != null) {
            duration = Duration.between(lastStatusChangedAt, clock.instant());
        }

        // Record transition metric with duration
        paymentTransitionMetrics.recordTransition(
                fromStatus,
                toStatus,
                trigger,
                duration
        );

        // 종결 전이 시 terminal 카운터 증가 (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED).
        // QUARANTINED 는 복구 대기 상태이므로 terminal 에 포함하지 않는다.
        if (resultEvent != null && isTerminalStatus(resultEvent.getStatus())) {
            paymentEventFlowMetrics.recordTerminal();
        }

        return result;
    }

    private static boolean isTerminalStatus(PaymentEventStatus status) {
        return status == PaymentEventStatus.DONE
                || status == PaymentEventStatus.FAILED
                || status == PaymentEventStatus.CANCELED
                || status == PaymentEventStatus.PARTIAL_CANCELED
                || status == PaymentEventStatus.EXPIRED;
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
