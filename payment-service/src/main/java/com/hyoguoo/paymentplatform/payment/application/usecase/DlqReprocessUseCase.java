package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.DlqReprocessPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentDlqReprocessMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@code events.confirmed.dlq} 유실 메시지 재주입 오케스트레이션 유스케이스.
 * <p>
 * 순서: (1) 로드 → (2) 사전검사(나이 게이트) → (3) {@link DlqReprocessPort} 발행 →
 * (4) 재주입 이력(횟수·결과) 로그 + 메트릭.
 * <p>
 * <b>나이 게이트 기준</b>: DLQ 레코드 자체의 나이가 아니라 <b>결제 종결 시각</b>
 * (DONE 상태의 {@code lastStatusChangedAt}) + 8일(P8D) — product-service
 * {@code stock_commit_dedupe} 멱등 토큰의 TTL 만료 시점과 동조한다
 * ({@link PaymentConfirmResultUseCase} 의 {@code STOCK_COMMITTED_TTL} 과 동일한 8일
 * = Kafka retention(7일) + 복구 버퍼(1일)). 이 시각을 넘겨 재주입하면 재발행된
 * stock-committed 이벤트가 결정적 키 멱등 방어 토큰 없이 중복 반영될 위험이 있어
 * 사전에 차단하고 수동 대사로 안내한다.
 * <p>
 * <b>미종결(DONE 이 아닌) 건은 나이 무관 통과</b>시킨다 — READY/IN_PROGRESS/QUARANTINED 는
 * 아직 재고 커밋(stock-committed)이 발행되지 않았거나 격리 상태라 이 멱등 창과 무관하고,
 * FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED 도 재고 커밋 대상이 아니다. 재발행은 원 토픽
 * EOS 컨슈머의 다층 멱등 방어(consumer dedupe / CAS / product 결정적 키)로 흡수된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqReprocessUseCase {

    /** DONE 종결 시각 기준 재주입 나이 게이트 — product stock_commit_dedupe TTL 과 동일한 8일(P8D). */
    private static final Duration STOCK_COMMIT_DEDUPE_TTL = Duration.ofDays(8);

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final DlqReprocessPort dlqReprocessPort;
    private final PaymentDlqReprocessMetrics paymentDlqReprocessMetrics;
    private final Clock clock;

    /**
     * 대상 주문의 DLQ 레코드를 사전검사 후 원 토픽으로 재주입한다.
     *
     * @param orderId 재주입 대상 주문 ID
     * @throws PaymentValidException DONE 종결 후 멱등 보장 기간(P8D)이 만료된 경우
     *                                ({@link PaymentErrorCode#DLQ_REPROCESS_AGE_GATE_EXCEEDED})
     */
    public void reprocess(String orderId) {
        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        if (isDedupeWindowExpired(event)) {
            paymentDlqReprocessMetrics.recordReprocess("blocked_age_gate");
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_DLQ_REPROCESS_BLOCKED,
                    () -> "orderId=" + orderId + " reason=stock_commit_dedupe_expired");
            throw PaymentValidException.of(PaymentErrorCode.DLQ_REPROCESS_AGE_GATE_EXCEEDED);
        }

        dlqReprocessPort.reprocess(orderId);

        paymentDlqReprocessMetrics.recordReprocess("reprocessed");
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_DLQ_REPROCESS_SUCCESS,
                () -> "orderId=" + orderId);
    }

    private boolean isDedupeWindowExpired(PaymentEvent event) {
        if (event.getStatus() != PaymentEventStatus.DONE) {
            return false;
        }
        Instant dedupeExpiry = event.getLastStatusChangedAt().plus(STOCK_COMMIT_DEDUPE_TTL);
        return clock.instant().isAfter(dedupeExpiry);
    }
}
