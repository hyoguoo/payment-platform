package com.hyoguoo.paymentplatform.payment.infrastructure.aspect;

import com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Trigger;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentEventFlowMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentTransitionMetrics;
import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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

        // 리컨실러 위임 메서드는 조건부 갱신이 0건이면 PaymentEvent 대신 null 을 반환한다 —
        // 실제로 옮기지 못한 건까지 애노테이션 고정값으로 전이를 기록하면 경합 스킵이 전이로 잡힌다.
        if (!(result instanceof PaymentEvent resultEvent)) {
            return result;
        }

        String toStatus = resultEvent.getStatus().name();
        String trigger = resolveTrigger(joinPoint, paymentStatusChange);

        Duration duration = null;
        if (lastStatusChangedAt != null) {
            duration = Duration.between(lastStatusChangedAt, clock.instant());
        }

        paymentTransitionMetrics.recordTransition(
                fromStatus,
                toStatus,
                trigger,
                duration
        );

        // 종결 판별은 PaymentEventStatus.isTerminal() SSOT 위임.
        // QUARANTINED 는 복구 대기 상태이므로 isTerminal() 이 false 를 반환 — 포함하지 않는다.
        if (resultEvent.getStatus().isTerminal()) {
            paymentEventFlowMetrics.recordTerminal();
        }

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

    /**
     * 전이 주체(trigger) 결정. 호출자가 {@link Trigger} 파라미터로 값을 넘겼으면 그 값을 우선 쓰고,
     * 없으면 애노테이션 고정값을 쓴다 — 한 메서드가 여러 흐름에서 불리는 경우를 위한 분기다.
     */
    private String resolveTrigger(ProceedingJoinPoint joinPoint, PaymentStatusChange paymentStatusChange) {
        return findTriggerParameter(joinPoint).orElseGet(paymentStatusChange::trigger);
    }

    private Optional<String> findTriggerParameter(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return Optional.empty();
        }
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        for (int i = 0; i < args.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof Trigger && args[i] instanceof String triggerValue) {
                    return Optional.of(triggerValue);
                }
            }
        }

        return Optional.empty();
    }
}
