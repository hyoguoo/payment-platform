package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 격리(QUARANTINED) 결제 안전 종결(FAILED) 오케스트레이션 유스케이스.
 * <p>
 * 순서(SCR-6 "보상 먼저" 원칙): (1) 로드 → (2) redis 재고 보상({@code compensateIfDecremented},
 * {@code @Transactional} 밖 — 외부 호출이 커넥션을 점유하지 않도록 PITFALLS §3 준수) →
 * (3) 도메인 전이 + CAS 저장 + AOP audit({@link PaymentCommandUseCase#markPaymentAsFailFromQuarantine},
 * 단일 {@code @Transactional}).
 * <p>
 * 보상 결과({@code OK}/{@code ALREADY_DONE}/{@code NO_DECREMENT}) 는 모두 정상 흐름이며
 * 결과와 무관하게 전이를 진행한다 — 재고 정리 성사 여부가 종결 자체를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineResolveUseCase {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final StockCachePort stockCachePort;
    private final PaymentCommandUseCase paymentCommandUseCase;

    /**
     * 격리 결제를 안전 종결(FAILED)로 복구한다.
     *
     * @param orderId 대상 주문 ID
     * @param reason  안전 종결 사유(필수 — 벤더 상태 확인 결과 포함)
     * @return 종결된 결제 이벤트
     * @throws PaymentValidException reason 이 누락(null/공백)된 경우
     */
    public PaymentEvent resolve(String orderId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw PaymentValidException.of(PaymentErrorCode.QUARANTINE_RESOLVE_REASON_REQUIRED);
        }

        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        // 비격리 상태(QUARANTINED 아님)면 비가역 redis 보상 호출 전에 즉시 거부한다 —
        // 최종 상태 검증은 도메인 전이(failFromQuarantine)에도 있으나, 보상은 그보다 먼저
        // 실행되므로 여기서 선제 검증하지 않으면 이미 DONE 등으로 종결된 orderId 에 대해
        // 유령 재고 보상이 먼저 나가버린다.
        if (event.getStatus() != PaymentEventStatus.QUARANTINED) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL_FROM_QUARANTINE);
        }

        // redis 보상은 TX 밖에서 먼저 수행 — 외부 호출이 DB 커넥션을 점유하지 않도록 한다.
        stockCachePort.compensateIfDecremented(orderId, event.getPaymentOrderList());

        PaymentEvent resolvedEvent = paymentCommandUseCase.markPaymentAsFailFromQuarantine(event, reason);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_QUARANTINE_RESOLVED,
                () -> "orderId=" + orderId + " reason=" + reason);

        return resolvedEvent;
    }
}
