package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryLookupResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgAttemptHistoryPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.presentation.port.PgAttemptHistoryViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * pg-service 확정 시도 이력 조회 — 조회 실패 흡수 + 조회 불가 폴백 판단을 담당하는
 * presentation 입력 포트 구현.
 *
 * <p>{@code PaymentAdminController}는 이 서비스가 반환하는 {@link PgAttemptHistoryLookupResult}
 * 를 모델에 담는 일만 하고, 조회 실패 여부 판단은 이 서비스가 전담한다.
 *
 * <p>try 범위는 {@link PgAttemptHistoryPort#getAttemptHistory} 호출과 그 결과를 담는
 * {@link PgAttemptHistoryLookupResult#available} 조립까지다 — 뷰 변환(presentation 계층의
 * {@code PgAttemptHistoryViewResponse.from})은 이 서비스가 하지 않으므로, pg-service
 * 조회 실패와 payment-service 자체 매핑 버그가 로그에서 섞이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgAttemptHistoryViewServiceImpl implements PgAttemptHistoryViewService {

    private final PgAttemptHistoryPort pgAttemptHistoryPort;

    @Override
    public PgAttemptHistoryLookupResult getAttemptHistory(String orderId) {
        try {
            return PgAttemptHistoryLookupResult.available(pgAttemptHistoryPort.getAttemptHistory(orderId));
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PAYMENT_GATEWAY, EventType.PG_SERVICE_UNEXPECTED,
                    () -> "orderId=" + orderId
                            + " errorType=" + e.getClass().getSimpleName()
                            + " error=" + e.getMessage());
            return PgAttemptHistoryLookupResult.unavailable();
        }
    }
}
