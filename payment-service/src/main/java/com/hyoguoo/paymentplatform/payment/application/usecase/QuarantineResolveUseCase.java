package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgVendorStatusPort;
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
 * 순서(SCR-6 "보상 먼저" 원칙 + 벤더 재확인): (1) 로드 → (2) 격리 상태 확인 →
 * (3) 벤더 상태 조회({@link PgVendorStatusPort#lookup}, 외부 호출이라 {@code @Transactional} 밖) →
 * (4) 판정 — 승인(APPROVED)이 확인되면 종결을 거부한다. 과금이 살아있는 건을 실패로 정리하면 되돌릴 수
 * 없기 때문이다 → (5) redis 재고 보상({@code compensateIfDecremented}, 역시 {@code @Transactional} 밖 —
 * 외부 호출이 커넥션을 점유하지 않도록 PITFALLS §3 준수) → (6) 도메인 전이 + CAS 저장 + AOP audit
 * ({@link PaymentCommandUseCase#markPaymentAsFailFromQuarantine}, 단일 {@code @Transactional}).
 * <p>
 * 조회·판정이 재고 보상보다 반드시 앞선다 — 보상은 비가역이라, 종결이 거부될 건에 보상이 먼저
 * 나가면 되돌릴 수 없다. 확인 불가(UNKNOWN)는 종결을 막지 않는다 — 막으면 벤더 장애가 길어질 때
 * 격리가 전혀 풀리지 않고 적체된다. 대신 조회 결과를 종결 사유에 덧붙여 감사 기록에 남긴다.
 * <p>
 * 보상 결과({@code OK}/{@code ALREADY_DONE}/{@code NO_DECREMENT}) 는 모두 정상 흐름이며
 * 결과와 무관하게 전이를 진행한다 — 재고 정리 성사 여부가 종결 자체를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineResolveUseCase {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PgVendorStatusPort pgVendorStatusPort;
    private final StockCachePort stockCachePort;
    private final PaymentCommandUseCase paymentCommandUseCase;

    /**
     * 격리 결제를 안전 종결(FAILED)로 복구한다.
     *
     * @param orderId 대상 주문 ID
     * @param reason  안전 종결 사유(필수) — 저장 시 벤더 상태 조회 결과가 덧붙는다
     * @return 종결된 결제 이벤트
     * @throws PaymentValidException  reason 이 누락(null/공백)된 경우
     * @throws PaymentStatusException 비격리 상태이거나, 벤더 조회에서 승인이 확인된 경우
     */
    public PaymentEvent resolve(String orderId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw PaymentValidException.of(PaymentErrorCode.QUARANTINE_RESOLVE_REASON_REQUIRED);
        }

        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        // 비격리 상태(QUARANTINED 아님)면 벤더 조회·비가역 redis 보상 호출 전에 즉시 거부한다 —
        // 최종 상태 검증은 도메인 전이(failFromQuarantine)에도 있으나, 조회·보상은 그보다 먼저
        // 실행되므로 여기서 선제 검증하지 않으면 이미 DONE 등으로 종결된 orderId 에 대해
        // 불필요한 벤더 호출과 유령 재고 보상이 먼저 나가버린다.
        if (event.getStatus() != PaymentEventStatus.QUARANTINED) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL_FROM_QUARANTINE);
        }

        // 벤더 조회는 TX 밖에서 먼저 수행 — 외부 호출이 DB 커넥션을 점유하지 않도록 한다.
        PgVendorStatusInfo vendorStatusInfo = pgVendorStatusPort.lookup(orderId);
        if (vendorStatusInfo.judgement() == PgVendorStatusJudgement.APPROVED) {
            throw PaymentStatusException.of(PaymentErrorCode.QUARANTINE_RESOLVE_VENDOR_APPROVED);
        }
        String resolvedReason = appendVendorStatus(reason, vendorStatusInfo);

        // redis 보상은 TX 밖에서 벤더 승인 배제가 끝난 뒤 수행 — 외부 호출이 DB 커넥션을 점유하지
        // 않도록 한다.
        stockCachePort.compensateIfDecremented(orderId, event.getPaymentOrderList());

        PaymentEvent resolvedEvent = paymentCommandUseCase.markPaymentAsFailFromQuarantine(event, resolvedReason);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_QUARANTINE_RESOLVED,
                () -> "orderId=" + orderId + " reason=" + resolvedReason);

        return resolvedEvent;
    }

    private String appendVendorStatus(String reason, PgVendorStatusInfo vendorStatusInfo) {
        String judgementText = vendorStatusInfo.judgement() == PgVendorStatusJudgement.FAILED ? "실패" : "확인불가";
        String vendorStatusText = vendorStatusInfo.vendorStatus() != null
                ? "(" + vendorStatusInfo.vendorStatus() + ")"
                : "";
        return reason + " / 벤더 상태 조회 결과: " + judgementText + vendorStatusText;
    }
}
