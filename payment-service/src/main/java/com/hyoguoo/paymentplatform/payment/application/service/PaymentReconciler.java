package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 결제 서비스 로컬 Reconciler — ADR-07.
 *
 * <p>각 scan() 호출에서 IN_FLIGHT(IN_PROGRESS) + timeout 초과 레코드를 READY 로 복원해
 * 재시도 스케줄러(OutboxWorker)가 재처리하도록 한다.
 *
 * <p>재고 캐시 발산 감지/RDB 기준 재설정 책임은 본 Reconciler 에서 제거되었다 — 새 재고 모델에서
 * Redis 캐시는 payment 가 자기 책임으로 관리한다 (선차감 + PG 결과별 보상). 부팅 시 시드는
 * 외부 부팅 스크립트가 product RDB → redis-stock 으로 일괄 SET 한다.
 */
@Slf4j
@Service
public class PaymentReconciler {

    private final PaymentEventRepository paymentEventRepository;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final long inFlightTimeoutSeconds;

    public PaymentReconciler(
            PaymentEventRepository paymentEventRepository,
            LocalDateTimeProvider localDateTimeProvider,
            @Value("${reconciler.in-flight-timeout-seconds:300}") long inFlightTimeoutSeconds
    ) {
        this.paymentEventRepository = paymentEventRepository;
        this.localDateTimeProvider = localDateTimeProvider;
        this.inFlightTimeoutSeconds = inFlightTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${reconciler.fixed-delay-ms:120000}")
    public void scan() {
        LocalDateTime now = localDateTimeProvider.now();
        resetStaleInFlightRecords(now);
    }

    /**
     * IN_FLIGHT(IN_PROGRESS) + timeout 초과 → READY 복원.
     * 재시도 스케줄러(OutboxWorker)가 READY 상태를 재처리한다.
     */
    private void resetStaleInFlightRecords(LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(inFlightTimeoutSeconds);
        List<PaymentEvent> staleEvents = paymentEventRepository.findInProgressOlderThan(cutoff);

        if (staleEvents.isEmpty()) {
            return;
        }
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                () -> "Reconciler: stale IN_FLIGHT 발견 " + staleEvents.size() + "건 → READY 복원");

        for (PaymentEvent event : staleEvents) {
            event.resetToReady(now);
            paymentEventRepository.saveOrUpdate(event);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> "Reconciler: orderId=" + event.getOrderId() + " → READY 복원 완료");
        }
    }
}
