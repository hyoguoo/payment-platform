package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusView;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgVendorStatusQueryService;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 관리자 화면 벤더 상태 조회 — 격리 종결 판단 전 벤더에 1회 묻고 승인·실패·확인 불가 세 값으로 접는다.
 *
 * <p>{@link PgStatusLookupPort#getStatusByOrderId} 를 재시도로 감싸지 않는다 — 요청 1회당 벤더 호출 1회.
 *
 * <p>조회 과정에서 나는 모든 실행 시 예외를 확인 불가로 접는다 — 게이트웨이 재시도 가능/불가 예외
 * 두 종만 잡으면, 벤더 전략이 처리 기록 없는 주문에 던지는 예외(예: 모의 벤더의
 * {@link UnsupportedOperationException})가 그대로 새어나가 5xx 가 된다. 재시도 소진으로 격리된
 * 주문이 정확히 이 경우라, 좁게 잡으면 이 엔드포인트가 풀려는 시나리오 자체가 깨진다. 예외
 * 타입과 사유는 로그에 남기므로 삼킴이 아니다 — 기존 조회 흡수 서비스(FCG)와 같은 형태다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgVendorStatusQueryServiceImpl implements PgVendorStatusQueryService {

    /**
     * 벤더 상태 조회 결과 중 APPROVED 로 매핑되는 PgPaymentStatus 집합.
     * Toss 기준: DONE = 결제 완료.
     */
    private static final Set<PgPaymentStatus> APPROVED_STATUSES = Set.of(PgPaymentStatus.DONE);

    /**
     * 벤더 상태 조회 결과 중 FAILED 로 매핑되는 PgPaymentStatus 집합.
     */
    private static final Set<PgPaymentStatus> FAILED_STATUSES = Set.of(
            PgPaymentStatus.ABORTED,
            PgPaymentStatus.CANCELED,
            PgPaymentStatus.PARTIAL_CANCELED,
            PgPaymentStatus.EXPIRED
    );

    private final PgInboxRepository pgInboxRepository;
    private final PgStatusLookupStrategySelector pgStatusLookupStrategySelector;
    private final Clock clock;

    @Override
    public PgVendorStatusView getVendorStatus(String orderId) {
        return pgInboxRepository.findByOrderId(orderId)
                .map(inbox -> queryVendor(orderId, inbox))
                .orElseGet(() -> PgVendorStatusView.unknown(clock.instant()));
    }

    private PgVendorStatusView queryVendor(String orderId, PgInbox inbox) {
        PgVendorType vendorType = resolveVendorType(inbox);
        if (vendorType == null) {
            return PgVendorStatusView.unknown(clock.instant());
        }
        return lookupOnce(orderId, vendorType);
    }

    private PgVendorType resolveVendorType(PgInbox inbox) {
        if (inbox.getVendorType() == null) {
            return null;
        }
        try {
            return PgVendorType.valueOf(inbox.getVendorType());
        } catch (IllegalArgumentException e) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_VENDOR_STATUS_QUERY_INDETERMINATE,
                    () -> "orderId=" + inbox.getOrderId() + " 알 수 없는 vendorType=" + inbox.getVendorType());
            return null;
        }
    }

    private PgVendorStatusView lookupOnce(String orderId, PgVendorType vendorType) {
        try {
            PgStatusLookupPort port = pgStatusLookupStrategySelector.select(vendorType);
            PgStatusResult statusResult = port.getStatusByOrderId(orderId);
            return judge(statusResult);
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_VENDOR_STATUS_QUERY_INDETERMINATE,
                    () -> "orderId=" + orderId + " vendorType=" + vendorType
                            + " exceptionType=" + e.getClass().getSimpleName() + " cause=" + e.getMessage());
            return PgVendorStatusView.unknown(clock.instant());
        }
    }

    private PgVendorStatusView judge(PgStatusResult statusResult) {
        PgPaymentStatus pgStatus = statusResult.status();
        Instant now = clock.instant();
        if (APPROVED_STATUSES.contains(pgStatus)) {
            return PgVendorStatusView.of(PgVendorStatusJudgement.APPROVED, pgStatus.name(), now);
        }
        if (FAILED_STATUSES.contains(pgStatus)) {
            return PgVendorStatusView.of(PgVendorStatusJudgement.FAILED, pgStatus.name(), now);
        }
        return PgVendorStatusView.of(PgVendorStatusJudgement.UNKNOWN, pgStatus.name(), now);
    }
}
