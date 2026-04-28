package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QUARANTINED 상태 전이 핸들러.
 * <p>
 * 본 핸들러 자체는 product RDB 보상을 수행하지 않는다 — 새 재고 모델에서 product RDB 는
 * APPROVED 시만 차감되므로 QUARANTINED 시 RDB 복원 대상이 아니다. redis-stock 선차감 캐시 보상은
 * 호출자(PaymentConfirmResultUseCase.handleQuarantined / handleApproved AMOUNT_MISMATCH)가
 * stockCachePort.increment 로 별도 수행한다.
 * <p>
 * 진입점:
 * (a) FCG — pg-service FCG 결과 status=QUARANTINED
 * (b) AMOUNT_MISMATCH — payment-service 가 amount 위변조 감지 시
 * (c) DLQ_CONSUMER — PaymentConfirmDlqConsumer 처리 후 status=QUARANTINED
 * <p>
 * 모든 진입점에서 본 핸들러는 TX 내 상태 전이만 수행한다 (markPaymentAsQuarantined 위임).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineCompensationHandler {

    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentEventRepository paymentEventRepository;

    /**
     * QUARANTINED 전이 수행 (TX 내).
     * <p>
     * 사전 가드: event 가 이미 종결 상태(DONE/FAILED 등)이면 no-op + INFO 로그만 남기고 반환한다.
     * 뒤늦은 QUARANTINED 메시지가 종결 상태 event 를 역전이시키는 것을 방지한다.
     * 도메인 {@link com.hyoguoo.paymentplatform.payment.domain.PaymentEvent#quarantine} 에도
     * 이중 가드(IllegalStateException)가 있다.
     *
     * @param orderId 주문 ID
     * @param reason  격리 사유
     */
    @Transactional
    public void handle(String orderId, String reason) {
        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        if (event.getStatus().isTerminal()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_QUARANTINE_NOOP_TERMINAL,
                    () -> "orderId=" + orderId + " status=" + event.getStatus() + " reason=" + reason);
            return;
        }

        PaymentEvent quarantinedEvent = paymentCommandUseCase.markPaymentAsQuarantined(event, reason);
        paymentEventRepository.saveOrUpdate(quarantinedEvent);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_QUARANTINE_TRANSITIONED,
                () -> "orderId=" + orderId + " reason=" + reason);
    }
}
