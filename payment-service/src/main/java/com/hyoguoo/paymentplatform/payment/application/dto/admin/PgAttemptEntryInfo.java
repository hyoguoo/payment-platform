package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import java.time.Instant;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;

/**
 * pg-service 확정 시도 이력 타임라인의 회차 1건 — payment 쪽 도메인 표현.
 * pg-service {@code PgAttemptHistoryEntry} 를 payment 도메인 DTO 로 옮긴 것.
 *
 * <p>세 시각의 의미는 pg-service 와 동일하다:
 * <ul>
 *   <li>{@code reservedAt} — 이 회차가 예약된 시점 (1회차는 결제 명령 수신 시각)</li>
 *   <li>{@code scheduledAt} — 백오프를 반영해 실행하기로 한 예정 시각</li>
 *   <li>{@code publishedAt} — 실제로 발행된 시각. 미발행이면 값이 없다</li>
 * </ul>
 *
 * <p>{@code attemptNo} 는 회차 정보를 읽지 못한 옛 행이면 {@link Optional#empty()} 다.
 * {@code exhausted} 는 소진(격리) 토픽 행인지, {@code normalAttempt} 는 결제 종결 이후
 * 뒤늦게 나간 유령 행이 아닌 정상 시도인지를 나타낸다.
 */
@Getter
@Builder
public class PgAttemptEntryInfo {

    private final Optional<Integer> attemptNo;
    private final Instant reservedAt;
    private final Instant scheduledAt;
    private final Instant publishedAt;
    private final boolean exhausted;
    private final boolean normalAttempt;
}
