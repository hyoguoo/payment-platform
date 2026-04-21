package com.hyoguoo.paymentplatform.pg.domain;

import java.security.SecureRandom;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * pg-service 내부 재시도 정책.
 * ADR-30: 재시도 = pg_outbox.available_at 지연 표현.
 *
 * <p>파라미터:
 * <ul>
 *   <li>base = 2s</li>
 *   <li>multiplier = 3</li>
 *   <li>MAX_ATTEMPTS = 4</li>
 *   <li>jitter = equal ±25% (uniform random in [-25%, +25%])</li>
 * </ul>
 *
 * <p>백오프 계산:
 * <pre>
 *   base_ms = base × multiplier^(attempt-1)
 *   jitter_factor = uniform(-0.25, +0.25)
 *   backoff = base_ms × (1 + jitter_factor)
 * </pre>
 *
 * <p>attempt별 기준값(jitter 제외):
 * <ul>
 *   <li>attempt=1: 2s × 3^0 = 2s  → [1.5s, 2.5s]</li>
 *   <li>attempt=2: 2s × 3^1 = 6s  → [4.5s, 7.5s]</li>
 *   <li>attempt=3: 2s × 3^2 = 18s → [13.5s, 22.5s]</li>
 *   <li>attempt=4: 2s × 3^3 = 54s → [40.5s, 67.5s]</li>
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RetryPolicy {

    private static final long BASE_MS = 2_000L;
    private static final int MULTIPLIER = 3;
    private static final double JITTER_RATIO = 0.25;

    /** 최대 재시도 횟수. attempt >= MAX_ATTEMPTS 면 DLQ 경로. */
    public static final int MAX_ATTEMPTS = 4;

    /**
     * 재시도 가능 여부.
     * attempt &lt; MAX_ATTEMPTS인 경우만 재시도 (attempt가 MAX_ATTEMPTS 이상이면 DLQ).
     *
     * @param attempt 현재 attempt 번호 (1부터 시작)
     * @return true — 재시도 진행, false — DLQ 경로
     */
    public static boolean shouldRetry(int attempt) {
        return attempt < MAX_ATTEMPTS;
    }

    /**
     * 지수 백오프 + equal jitter 계산.
     *
     * @param attempt 현재 attempt 번호 (1부터 시작)
     * @param rng     SecureRandom (jitter 계산용)
     * @return 대기 시간 Duration
     */
    public static Duration computeBackoff(int attempt, SecureRandom rng) {
        long baseMs = BASE_MS * (long) Math.pow(MULTIPLIER, attempt - 1);
        // equal jitter: uniform(-0.25, +0.25) × base
        double jitterFactor = (rng.nextDouble() * 2.0 * JITTER_RATIO) - JITTER_RATIO;
        long backoffMs = Math.round(baseMs * (1.0 + jitterFactor));
        return Duration.ofMillis(backoffMs);
    }
}
