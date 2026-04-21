package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.QuarantineCompensationHandler;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * QUARANTINED compensation pending 레코드 주기 스캔 스케줄러.
 * <p>
 * QuarantineCompensationHandler의 TX 밖 Redis INCR이 실패하거나 프로세스 크래시로
 * quarantineCompensationPending 플래그가 잔존하는 경우, 이 스케줄러가 주기적으로 스캔하여
 * retryStockRollback()을 재시도한다.
 * <p>
 * ADR-15(§2-2b-3) Scheduler 재시도 경로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuarantineCompensationScheduler {

    private final PaymentEventRepository paymentEventRepository;
    private final QuarantineCompensationHandler quarantineCompensationHandler;

    @Scheduled(fixedDelayString = "${scheduler.quarantine-compensation.fixed-delay-ms:60000}")
    public void scan() {
        List<PaymentEvent> pendingEvents = paymentEventRepository.findByQuarantineCompensationPendingTrue();
        if (pendingEvents.isEmpty()) {
            return;
        }
        log.info("QuarantineCompensationScheduler: pending 재고 복구 대상 {}건 발견", pendingEvents.size());
        for (PaymentEvent event : pendingEvents) {
            quarantineCompensationHandler.retryStockRollback(event.getOrderId());
        }
    }
}
