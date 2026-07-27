package com.hyoguoo.paymentplatform.pg.application.dto;

import java.time.Instant;
import java.util.Optional;

/**
 * 시도 이력 타임라인의 회차 1건.
 *
 * <p>세 시각의 의미:
 * <ul>
 *   <li>{@code reservedAt} — 직전 시도 실패 직후 이 회차가 예약된 시점 (outbox row 의 created_at).
 *       1회차는 예약이라는 개념이 없어 pg_inbox 의 결제 명령 수신 시각을 그대로 담는다.</li>
 *   <li>{@code scheduledAt} — 백오프를 반영해 실행하기로 한 예정 시각 (outbox row 의 available_at).
 *       1회차는 즉시 실행되므로 값이 없다.</li>
 *   <li>{@code publishedAt} — 실제로 Kafka 에 발행된 시각 (outbox row 의 processed_at). 아직
 *       발행되지 않은 회차는 값이 없다 — 실행 예정 상태로 취급한다.</li>
 * </ul>
 *
 * <p>{@code attemptNo} 는 outbox row 의 {@code headers_json} 에서 읽는다. 헤더가 없거나 파싱에
 * 실패하면 {@link Optional#empty()} — 회차 미지 상태이며 예외를 던지지 않는다.
 *
 * <p>{@code exhausted} 는 이 행이 소진(격리) 토픽 행인지를 나타낸다.
 * {@code normalAttempt} 는 결제가 종결된 뒤에도 뒤늦게 나간 유령 행이 아닌 정상 시도인지를 나타낸다
 * — 결제가 비종결이면 판정을 건너뛰고 항상 정상 시도로 취급한다.
 *
 * <p>원문 컬럼({@code payload} / {@code headers_json})과 벤더 결제 키는 이 DTO 에 담지 않는다.
 */
public record PgAttemptHistoryEntry(
        Optional<Integer> attemptNo,
        Instant reservedAt,
        Instant scheduledAt,
        Instant publishedAt,
        boolean exhausted,
        boolean normalAttempt
) {

    /**
     * 최초 시도 — outbox 행이 아니라 pg_inbox 의 결제 명령 수신 시각으로 구성한다.
     * 항상 1회차 · 정상 시도로 취급한다 — 이 시점 이후에만 종결이 발생할 수 있어
     * 미실행 판정 대상이 될 수 없다.
     *
     * @param receivedAt pg_inbox.created_at (결제 명령 수신 시각)
     */
    public static PgAttemptHistoryEntry initial(Instant receivedAt) {
        return new PgAttemptHistoryEntry(Optional.of(1), receivedAt, null, null, false, true);
    }
}
