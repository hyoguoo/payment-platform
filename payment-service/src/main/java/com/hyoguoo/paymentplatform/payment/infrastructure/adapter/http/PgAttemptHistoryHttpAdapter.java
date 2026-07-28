package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptEntryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgAttemptHistoryPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.exception.PgAttemptHistoryServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgAttemptEntryResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgAttemptHistoryResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.PgFeignClient;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * pg-service 확정 시도 이력 조회 HTTP 어댑터.
 * PgAttemptHistoryPort 구현체 — 결제 승인 경로가 쓰는 ProductHttpAdapter/UserHttpAdapter 와
 * 분리된 관리자 조회 전용 어댑터라 별도 활성화 프로파일 없이 항상 등록된다.
 *
 * <p>4xx/5xx → 도메인 예외 매핑은 {@link PgFeignClient} 에 연결된 ErrorDecoder(PgFeignConfig) 가
 * 담당한다. transport-level 예외(RetryableException) 는 이 클래스에서
 * PgAttemptHistoryServiceRetryableException 으로 매핑한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgAttemptHistoryHttpAdapter implements PgAttemptHistoryPort {

    private final PgFeignClient pgFeignClient;

    @Override
    public PgAttemptHistoryInfo getAttemptHistory(String orderId) {
        try {
            PgAttemptHistoryResponse response = pgFeignClient.getAttemptHistory(orderId);
            return toPgAttemptHistoryInfo(response);
        } catch (feign.RetryableException e) {
            LogFmt.warn(log, LogDomain.PAYMENT_GATEWAY, EventType.PG_SERVICE_RETRYABLE,
                    () -> "transport=" + e.getMessage());
            throw PgAttemptHistoryServiceRetryableException.of(PaymentErrorCode.PG_SERVICE_UNAVAILABLE);
        }
    }

    private static PgAttemptHistoryInfo toPgAttemptHistoryInfo(PgAttemptHistoryResponse response) {
        return PgAttemptHistoryInfo.builder()
                .orderId(response.orderId())
                .found(response.found())
                .finalStatus(response.finalStatus())
                .finalizedAt(response.finalizedAt())
                .reasonCode(response.reasonCode())
                .attempts(toAttemptEntryInfoList(response.attempts()))
                .build();
    }

    private static List<PgAttemptEntryInfo> toAttemptEntryInfoList(List<PgAttemptEntryResponse> attempts) {
        return attempts.stream()
                .map(PgAttemptHistoryHttpAdapter::toAttemptEntryInfo)
                .collect(Collectors.toList());
    }

    private static PgAttemptEntryInfo toAttemptEntryInfo(PgAttemptEntryResponse entry) {
        return PgAttemptEntryInfo.builder()
                .attemptNo(Optional.ofNullable(entry.attemptNo()))
                .reservedAt(entry.reservedAt())
                .scheduledAt(entry.scheduledAt())
                .publishedAt(entry.publishedAt())
                .exhausted(entry.exhausted())
                .normalAttempt(entry.normalAttempt())
                .build();
    }
}
