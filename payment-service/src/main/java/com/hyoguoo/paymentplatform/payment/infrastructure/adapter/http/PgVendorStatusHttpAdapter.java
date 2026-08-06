package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgVendorStatusPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgVendorStatusResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.PgVendorStatusFeignClient;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * pg-service 벤더 상태 조회 HTTP 어댑터 — PgVendorStatusPort 구현체.
 *
 * <p>{@link PgAttemptHistoryHttpAdapter} 와 달리 어떤 실행 시 예외도 밖으로 던지지 않는다 —
 * 통신 예외(feign.RetryableException)든 pg 자체 오류({@code PgVendorStatusQueryFailedException})든
 * 모두 확인 불가로 접어 반환한다. 이 포트를 쓰는 격리 종결 판정이 승인/실패/확인불가 세 갈래 분기
 * 하나로 끝나야 하기 때문이다 — 예외 처리 분기를 늘리면 그 경로가 안전장치를 새게 만든다. 예외
 * 타입과 사유는 로그에 남기므로 삼킴이 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgVendorStatusHttpAdapter implements PgVendorStatusPort {

    private final PgVendorStatusFeignClient pgVendorStatusFeignClient;
    private final Clock clock;

    @Override
    public PgVendorStatusInfo lookup(String orderId) {
        try {
            PgVendorStatusResponse response = pgVendorStatusFeignClient.getVendorStatus(orderId);
            return toInfo(response);
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PAYMENT_GATEWAY, EventType.PG_VENDOR_STATUS_QUERY_INDETERMINATE,
                    () -> "orderId=" + orderId + " exceptionType=" + e.getClass().getSimpleName()
                            + " cause=" + e.getMessage());
            return PgVendorStatusInfo.unknown(clock.instant());
        }
    }

    private static PgVendorStatusInfo toInfo(PgVendorStatusResponse response) {
        return PgVendorStatusInfo.of(
                PgVendorStatusJudgement.valueOf(response.judgement()),
                response.vendorStatus(),
                response.queriedAt()
        );
    }
}
