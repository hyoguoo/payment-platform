package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentProcessRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentProcessUseCase {

    private final PaymentProcessRepository paymentProcessRepository;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Transactional
    public PaymentProcess createProcessingJob(String orderId) {
        PaymentProcess paymentProcess = PaymentProcess.createProcessing(orderId);
        return paymentProcessRepository.save(paymentProcess);
    }

    @Transactional
    public PaymentProcess completeJob(String orderId) {
        PaymentProcess paymentProcess = getPaymentProcessByOrderId(orderId);

        if (paymentProcess.isFinished()) {
            return paymentProcessRepository.save(paymentProcess);
        }

        LocalDateTime completedAt = localDateTimeProvider.now();
        paymentProcess.complete(completedAt);
        return paymentProcessRepository.save(paymentProcess);
    }

    @Transactional
    public PaymentProcess failJob(String orderId, String failureReason) {
        PaymentProcess paymentProcess = getPaymentProcessByOrderId(orderId);

        if (paymentProcess.isFinished()) {
            return paymentProcessRepository.save(paymentProcess);
        }

        LocalDateTime failedAt = localDateTimeProvider.now();
        paymentProcess.fail(failedAt, failureReason);
        return paymentProcessRepository.save(paymentProcess);
    }

    @Transactional(readOnly = true)
    public List<PaymentProcess> findAllProcessingJobs() {
        return paymentProcessRepository.findAllByStatus(PaymentProcessStatus.PROCESSING);
    }

    private PaymentProcess getPaymentProcessByOrderId(String orderId) {
        return paymentProcessRepository.findByOrderId(orderId)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_PROCESS_NOT_FOUND));
    }
}
