package com.hyoguoo.paymentplatform.payment.application.aspect;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.publisher.PaymentEventPublisher;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
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
public class PaymentHistoryAspect {

    private final PaymentEventPublisher paymentEventPublisher;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Around("@annotation(publishHistory)")
    public Object publishHistoryEvent(ProceedingJoinPoint joinPoint, PublishPaymentHistory publishHistory)
            throws Throwable {
        PaymentEvent beforeEvent = findPaymentEvent(joinPoint.getArgs());
        PaymentEventStatus beforeStatus = beforeEvent != null ? beforeEvent.getStatus() : null;

        String reason = findReasonParameter(joinPoint);

        try {
            Object result = joinPoint.proceed();

            processResultAndPublishEvent(beforeStatus, result, reason, publishHistory);

            return result;
        } catch (Exception e) {
            log.error("Error occurred while processing payment: {}", e.getMessage(), e);
            throw e;
        }
    }

    private PaymentEvent findPaymentEvent(Object[] args) {
        return Arrays.stream(args)
                .filter(PaymentEvent.class::isInstance)
                .map(PaymentEvent.class::cast)
                .findFirst()
                .orElse(null);
    }

    private String findReasonParameter(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        for (int i = 0; i < args.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof Reason && args[i] instanceof String) {
                    return (String) args[i];
                }
            }
        }

        return null;
    }

    private void processResultAndPublishEvent(
            PaymentEventStatus beforeStatus,
            Object result,
            String reason,
            PublishPaymentHistory publishHistory
    ) {
        LocalDateTime occurredAt = localDateTimeProvider.now();
        if (!(result instanceof PaymentEvent afterEvent)) {
            return;
        }

        switch (publishHistory.action()) {
            case "created" -> {
                String createdReason = "New payment created";
                paymentEventPublisher.publishPaymentCreated(afterEvent, createdReason, occurredAt);
            }
            case "retry" -> {
                String retryReason = String.format("Retry attempt #%d", afterEvent.getRetryCount());
                paymentEventPublisher.publishRetryAttempt(afterEvent, beforeStatus, retryReason, occurredAt);
            }
            case "changed" -> {
                String changeReason = reason != null ? reason : "Payment is in progress successfully.";
                paymentEventPublisher.publishStatusChange(afterEvent, beforeStatus, changeReason, occurredAt);
            }
            default -> log.warn("Unknown action '{}' in @PublishPaymentHistory annotation.", publishHistory.action());
        }
    }
}
