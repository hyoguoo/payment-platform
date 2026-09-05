package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 결제 서비스 로컬 Reconciler — IN_FLIGHT timeout 되돌리기 전담.
 *
 * <p>각 scan() 호출에서 IN_FLIGHT(IN_PROGRESS) + timeout 초과 레코드를 결과 대기(AWAITING_RESULT)
 * 상태로 옮긴다. 재처리를 유발하지 않으며, 뒤늦게 도착하는 확정 결과를 여전히 받아들일 수 있게
 * 하는 것이 목적이다.
 *
 * <p>재고 캐시 발산 감지/RDB 기준 재설정 책임은 본 Reconciler 에서 제거되었다 — 새 재고 모델에서
 * Redis 캐시는 payment 가 자기 책임으로 관리한다 (선차감 + PG 결과별 보상). 부팅 시 시드는
 * 외부 부팅 스크립트가 product RDB → redis-stock 으로 일괄 SET 한다.
 */
@Slf4j
@Service
public class PaymentReconciler {

    private final PaymentEventRepository paymentEventRepository;
    private final Clock clock;
    private final long inFlightTimeoutSeconds;

    public PaymentReconciler(
            PaymentEventRepository paymentEventRepository,
            Clock clock,
            @Value("${reconciler.in-flight-timeout-seconds:300}") long inFlightTimeoutSeconds
    ) {
        this.paymentEventRepository = paymentEventRepository;
        this.clock = clock;
        this.inFlightTimeoutSeconds = inFlightTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${reconciler.fixed-delay-ms:120000}")
    public void scan() {
        Instant now = clock.instant();
        resetStaleInFlightRecords(now);
    }

    /**
     * IN_FLIGHT(IN_PROGRESS) + timeout 초과 → 결과 대기(AWAITING_RESULT) 로 되돌린다.
     */
    private void resetStaleInFlightRecords(Instant now) {
        Instant cutoff = now.minus(Duration.ofSeconds(inFlightTimeoutSeconds));
        List<PaymentEvent> staleEvents = paymentEventRepository.findInProgressOlderThan(cutoff);

        if (staleEvents.isEmpty()) {
            return;
        }
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                () -> "Reconciler: stale IN_FLIGHT 발견 " + staleEvents.size() + "건 → 결과 대기로 되돌림");

        for (PaymentEvent event : staleEvents) {
            event.resetToAwaitingResult(now);
            paymentEventRepository.saveOrUpdate(event);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> "Reconciler: orderId=" + event.getOrderId() + " → 결과 대기로 되돌림 완료");
        }
    }
}
