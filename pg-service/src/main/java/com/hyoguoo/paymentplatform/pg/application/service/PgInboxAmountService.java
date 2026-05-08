package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service business inbox amount 컬럼 저장 규약 서비스.
 *
 * <p>WARNING: 이 서비스는 main 코드에서 호출처가 없는 dead service 입니다.
 * PCS-9 에서 포트 메서드(transitNoneToInProgress) 삭제에 따른 컴파일 에러 해소만 진행합니다.
 * dead service 자체 제거는 별 토픽 / 사용자 확인 후 처리합니다.
 *
 * <p>amount 는 아래 3경로로만 기록된다 — 다른 경로의 직접 UPDATE 금지.
 *
 * <ol>
 *   <li>(a) 직접 IN_PROGRESS 신설: {@link #recordPayloadAmount} — command payload amount 기록.</li>
 *   <li>(b) IN_PROGRESS→APPROVED: {@link #validateAndApprove} — 벤더 2자 대조 통과값 기록.</li>
 *   <li>(c) 직접 APPROVED 신설: {@link #recordAndApproveDirect} — payload vs vendor 2자 대조 통과값 기록.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgInboxAmountService {

    private static final String REASON_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private final PgInboxRepository pgInboxRepository;

    // -----------------------------------------------------------------------
    // (a) 직접 IN_PROGRESS 신설: payload amount 기록
    // -----------------------------------------------------------------------

    /**
     * 직접 IN_PROGRESS 신설 + payload amount 기록.
     * PCS-9: transitNoneToInProgress → transitDirectToInProgress 교체 (PENDING 우회).
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
        // PCS-9: transitNoneToInProgress 삭제 → transitDirectToInProgress 교체 (PENDING 우회)
        pgInboxRepository.transitDirectToInProgress(orderId, amountLong);
        LogFmt.info(log, LogDomain.PG, EventType.PG_INBOX_AMOUNT_RECORDED,
                () -> "orderId=" + orderId + " amount=" + amountLong);
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
            LogFmt.warn(log, LogDomain.PG, EventType.PG_INBOX_AMOUNT_NULL_QUARANTINED,
                    () -> "orderId=" + orderId);
            pgInboxRepository.transitToQuarantined(orderId, REASON_AMOUNT_MISMATCH);
            return;
        }

        if (inbox.getAmount().longValue() != vendorAmount) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_INBOX_AMOUNT_MISMATCH_QUARANTINED,
                    () -> "orderId=" + orderId + " inboxAmount=" + inbox.getAmount() + " vendorAmount=" + vendorAmount);
            pgInboxRepository.transitToQuarantined(orderId, REASON_AMOUNT_MISMATCH);
            return;
        }

        pgInboxRepository.transitToApproved(orderId, buildApprovedPayload(orderId, vendorAmount));
        LogFmt.info(log, LogDomain.PG, EventType.PG_INBOX_AMOUNT_APPROVED,
                () -> "orderId=" + orderId + " amount=" + vendorAmount);
    }

    // -----------------------------------------------------------------------
    // (c) NONE → APPROVED 직접 전이 (pg DB 레코드가 부재한 상태에서 벤더만 APPROVED 인 경로)
    // -----------------------------------------------------------------------

    /**
     * 직접 APPROVED 신설 (pg DB 부재 경로).
     * payload vs vendor 2자 대조 통과값을 amount로 기록하며 즉시 APPROVED 전이.
     * PCS-9: transitNoneToInProgress → transitDirectToTerminal(APPROVED) 교체 (PENDING 우회).
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

        // PCS-9: transitNoneToInProgress 삭제 → transitDirectToTerminal(APPROVED) 교체 (PENDING 우회)
        String approvedPayload = buildApprovedPayload(orderId, vendorAmount);
        pgInboxRepository.transitDirectToTerminal(orderId, payloadLong,
                com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus.APPROVED,
                approvedPayload, null);
        LogFmt.info(log, LogDomain.PG, EventType.PG_INBOX_AMOUNT_DIRECT_APPROVED,
                () -> "orderId=" + orderId + " amount=" + payloadLong);
    }

    // -----------------------------------------------------------------------
    // 내부 유틸
    // -----------------------------------------------------------------------

    private String buildApprovedPayload(String orderId, long amount) {
        return "{\"orderId\":\"" + orderId + "\",\"status\":\"APPROVED\",\"amount\":" + amount + "}";
    }
}
