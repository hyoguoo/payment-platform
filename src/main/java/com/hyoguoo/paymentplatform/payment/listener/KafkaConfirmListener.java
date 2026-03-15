package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

// Plan 03에서 완성 예정 — 현재는 컴파일 스텁
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConfirmListener {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;

    @RetryableTopic(attempts = "6")
    public void consume(String message) {
        throw new UnsupportedOperationException("Plan 03에서 구현 예정");
    }

    public void handleDlt(String message) {
        throw new UnsupportedOperationException("Plan 03에서 구현 예정");
    }
}
