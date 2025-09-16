package com.hyoguoo.paymentplatform.payment.application.aspect;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.publisher.PaymentEventPublisher;
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
public class PaymentHistoryAspect {

    private final PaymentEventPublisher paymentEventPublisher;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Around("@annotation(publishHistory)")
    public Object publishHistoryEvent(ProceedingJoinPoint joinPoint, PublishPaymentHistory publishHistory)
            throws Throwable {
        return joinPoint.proceed();
    }
}


