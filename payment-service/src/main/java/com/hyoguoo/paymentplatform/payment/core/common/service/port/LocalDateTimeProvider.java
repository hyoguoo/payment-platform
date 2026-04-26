package com.hyoguoo.paymentplatform.payment.core.common.service.port;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 시간 소스 추상화 인터페이스.
 *
 * <p>{@link #nowInstant()} 도 함께 노출해 Instant 기반 시간 소스도 같은 Provider 로 일관되게 주입한다.
 * 테스트에서는 fixed clock 을 주입해 시간 결정성을 보장한다.
 *
 * <p>기본 구현: {@link Clock#systemUTC()} 기반.
 */
public interface LocalDateTimeProvider {

    /** UTC 기준 현재 LocalDateTime. */
    LocalDateTime now();

    /**
     * UTC 기준 현재 Instant.
     *
     * <p>K5 추가: Instant.now() 직접 호출 대신 이 메서드를 통해 시간 소스를 주입받는다.
     * 기본 구현은 {@code Instant.now()} 위임 — 기존 구현체 호환 유지.
     */
    default Instant nowInstant() {
        return Instant.now();
    }
}
