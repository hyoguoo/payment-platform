package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.converter.AmountConverter;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service business inbox amount 컬럼 저장 규약 서비스.
 * ADR-21 보강(T2b-04) — 불변식 4c: amount는 아래 3경로로만 기록된다.
 *
 * <ol>
 *   <li>(a) NONE→IN_PROGRESS: {@link #recordPayloadAmount} — command payload amount 기록.</li>
 *   <li>(b) IN_PROGRESS→APPROVED: {@link #validateAndApprove} — 벤더 2자 대조 통과값 기록.</li>
 *   <li>(c) NONE→APPROVED 직접: {@link #recordAndApproveDirect} — payload vs vendor 2자 대조 통과값 기록.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgInboxAmountService {

    private static final String REASON_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private final PgInboxRepository pgInboxRepository;

    // -----------------------------------------------------------------------
    // (a) NONE → IN_PROGRESS: payload amount 기록
    // -----------------------------------------------------------------------

    /**
     * NONE→IN_PROGRESS 전이 시 command payload amount를 pg_inbox.amount에 기록한다.
     *
     * <p>BigDecimal 검증: scale=0 강제(소수점 거부) + 음수 거부.
     *
     * @param orderId      주문 ID
     * @param payloadAmount command payload 금액 (scale=0, 양수)
     * @throws ArithmeticException      scale&gt;0 또는 음수 입력 시
     * @throws IllegalArgumentException null 입력 시
     */
    @Transactional
    public void recordPayloadAmount(String orderId, BigDecimal payloadAmount) {
        long amountLong = AmountConverter.fromBigDecimalStrict(payloadAmount);
        boolean transitioned = pgInboxRepository.transitNoneToInProgress(orderId, amountLong);
        if (!transitioned) {
            log.info("PgInboxAmountService: NONE→IN_PROGRESS 전이 실패(이미 선점) orderId={}", orderId);
        } else {
            log.info("PgInboxAmountService: payload amount 기록 완료 orderId={} amount={}", orderId, amountLong);
        }
    }

    // -----------------------------------------------------------------------
    // (b) IN_PROGRESS → APPROVED: 벤더 2자 대조 + APPROVED 전이
    // -----------------------------------------------------------------------

    /**
     * IN_PROGRESS→APPROVED 전이 시 inbox.amount vs vendorAmount 2자 대조를 수행한다.
     *
     * <ul>
     *   <li>일치 → APPROVED 전이</li>
     *   <li>불일치 → QUARANTINED 전이 + reason_code=AMOUNT_MISMATCH (pg_inbox APPROVED 차단)</li>
     * </ul>
     *
     * @param orderId      주문 ID
     * @param vendorAmount 벤더 재조회 금액 (long)
     */
    @Transactional
    public void validateAndApprove(String orderId, long vendorAmount) {
        PgInbox inbox = pgInboxRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "validateAndApprove: inbox not found orderId=" + orderId));

        if (inbox.getAmount() == null) {
            log.warn("PgInboxAmountService: inbox.amount null — QUARANTINED 전이 orderId={}", orderId);
            pgInboxRepository.transitToQuarantined(orderId, REASON_AMOUNT_MISMATCH);
            return;
        }

        if (inbox.getAmount().longValue() != vendorAmount) {
            log.warn("PgInboxAmountService: 2자 금액 불일치 — QUARANTINED+AMOUNT_MISMATCH orderId={} inboxAmount={} vendorAmount={}",
                    orderId, inbox.getAmount(), vendorAmount);
            pgInboxRepository.transitToQuarantined(orderId, REASON_AMOUNT_MISMATCH);
            return;
        }

        pgInboxRepository.transitToApproved(orderId, buildApprovedPayload(orderId, vendorAmount));
        log.info("PgInboxAmountService: 2자 대조 통과 — APPROVED 전이 orderId={} amount={}", orderId, vendorAmount);
    }

    // -----------------------------------------------------------------------
    // (c) NONE → APPROVED 직접 전이 (pg DB 부재 경로 ADR-05 보강 6번)
    // -----------------------------------------------------------------------

    /**
     * NONE→APPROVED 직접 전이 (pg DB 부재 경로).
     * payload vs vendor 2자 대조 통과값을 amount로 기록하며 즉시 APPROVED 전이.
     *
     * <p>payload != vendor → 즉시 {@link IllegalStateException} (저장 자체를 차단).
     *
     * @param orderId       주문 ID
     * @param payloadAmount command payload 금액 (scale=0, 양수)
     * @param vendorAmount  벤더 재조회 금액 (long)
     */
    @Transactional
    public void recordAndApproveDirect(String orderId, BigDecimal payloadAmount, long vendorAmount) {
        long payloadLong = AmountConverter.fromBigDecimalStrict(payloadAmount);

        if (payloadLong != vendorAmount) {
            throw new IllegalStateException(
                    "recordAndApproveDirect: payload/vendor amount mismatch orderId=" + orderId
                            + " payloadAmount=" + payloadLong + " vendorAmount=" + vendorAmount);
        }

        pgInboxRepository.transitNoneToInProgress(orderId, payloadLong);
        pgInboxRepository.transitToApproved(orderId, buildApprovedPayload(orderId, vendorAmount));
        log.info("PgInboxAmountService: 직접 APPROVED 전이 완료 orderId={} amount={}", orderId, payloadLong);
    }

    // -----------------------------------------------------------------------
    // 내부 유틸
    // -----------------------------------------------------------------------

    private String buildApprovedPayload(String orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"status\":\"APPROVED\",\"amount\":" + amount + "}";
    }
}
