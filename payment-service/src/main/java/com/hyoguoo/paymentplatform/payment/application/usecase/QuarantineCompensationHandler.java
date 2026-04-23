package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QUARANTINED 상태 전이 핸들러.
 * <p>
 * ADR-15: QUARANTINED는 "벤더 상태 불명" 홀딩 상태. 재고 복구 대상이 아니다.
 * 재고 복구는 FAIL 경로에서만 발생(FailureCompensationService → stock.events.restore).
 * QUARANTINED → FAIL 전이는 운영자 수동으로만 수행.
 * <p>
 * 진입점:
 * (a) FCG — pg-service FCG 결과 status=QUARANTINED
 * (b) DLQ_CONSUMER — PaymentConfirmDlqConsumer 처리 후 status=QUARANTINED
 * <p>
 * 두 진입점 모두 TX 내 상태 전이만 수행. Redis/StockCachePort 접촉 없음.
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
     *
     * @param orderId 주문 ID
     * @param reason  격리 사유
     */
    @Transactional
    public void handle(String orderId, String reason) {
        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        PaymentEvent quarantinedEvent = paymentCommandUseCase.markPaymentAsQuarantined(event, reason);
        paymentEventRepository.saveOrUpdate(quarantinedEvent);

        log.info("QuarantineCompensationHandler: QUARANTINED 전이 완료 orderId={} reason={}", orderId, reason);
    }
}
