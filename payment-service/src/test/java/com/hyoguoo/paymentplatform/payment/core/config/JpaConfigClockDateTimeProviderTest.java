package com.hyoguoo.paymentplatform.payment.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

/**
 * JpaConfig.clockDateTimeProvider() 빈 계약 단위 단정.
 *
 * <p>D4 전환 — clockDateTimeProvider() 가 {@link Instant} 를 반환하는 계약을 검증한다.
 * <ol>
 *   <li>빈 동작 계약: {@code clockDateTimeProvider()} 가 주입받은 {@code Clock} 의 고정 instant 를 반환한다.
 *       JVM TZ 에 영향을 받지 않는다.
 *   <li>결정성: {@link Clock#fixed(Instant, java.time.ZoneId)} 를 주입하면 반환값이
 *       해당 {@link Instant} 와 정확히 동치다.
 * </ol>
 *
 * <p>이 테스트는 Spring ApplicationContext 를 로드하지 않는 순수 단위 테스트다.
 * Spring JPA Auditing 연결 단정({@code dateTimeProviderRef} 가 {@code clockDateTimeProvider} 빈을
 * 실제로 가리키는지)은
 * {@link JpaAuditingProviderWiringTest} 에서 별도로 검증한다.
 *
 * <p>회귀 시나리오:
 * <ul>
 *   <li>{@code clockDateTimeProvider()} 구현이 {@code LocalDateTime} 을 반환하도록 되돌아가면
 *       {@code isInstanceOf(Instant.class)} 단정이 실패한다 — D4 전환 회귀를 잡는다.
 *   <li>고정 Clock 의 instant 가 그대로 반환되지 않으면(다른 값으로 변환되면)
 *       동치 단정이 실패한다.
 * </ul>
 */
@DisplayName("JpaConfig.clockDateTimeProvider() 빈 계약 단위 단정")
class JpaConfigClockDateTimeProviderTest {

    /**
     * D4 전환 가드 — clockDateTimeProvider() 가 고정 Clock 의 Instant 를 그대로 반환한다.
     *
     * <p>고정 instant 기준으로 동일 Instant 가 반환되는지 직접 단정한다.
     */
    @Test
    @DisplayName("D4 단위 가드 — clockDateTimeProvider() 가 Clock 고정 instant 를 반환한다")
    void clockDateTimeProvider_withFixedClock_returnsInstant() {
        // given
        Instant fixedInstant = Instant.parse("2026-01-01T09:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        JpaConfig jpaConfig = new JpaConfig(fixedClock);

        DateTimeProvider provider = jpaConfig.clockDateTimeProvider();

        // when
        Optional<Instant> result = provider.getNow()
                .filter(ta -> ta instanceof Instant)
                .map(ta -> (Instant) ta);

        // then
        assertThat(result)
                .as("clockDateTimeProvider() 는 고정 Clock 의 Instant 를 그대로 반환해야 한다")
                .isPresent()
                .contains(fixedInstant);
    }

    /**
     * D4 단위 가드 — KST Zone Clock 을 주입해도 반환값은 동일 Instant 이어야 한다.
     *
     * <p>Zone 과 무관하게 동일 instant 를 반환한다는 계약을 검증한다.
     */
    @Test
    @DisplayName("D4 단위 가드 — KST Zone Clock 을 주입해도 반환값은 동일 Instant 다")
    void clockDateTimeProvider_withKstZonedClock_returnsInstant() {
        // given — KST(+09:00) zone 의 고정 Clock. instant 는 UTC 기준이므로 오해 없음
        Instant fixedInstant = Instant.parse("2026-01-01T00:00:00Z");
        Clock kstClock = Clock.fixed(fixedInstant, ZoneOffset.ofHours(9));
        JpaConfig jpaConfig = new JpaConfig(kstClock);

        DateTimeProvider provider = jpaConfig.clockDateTimeProvider();

        // when
        Optional<Instant> result = provider.getNow()
                .filter(ta -> ta instanceof Instant)
                .map(ta -> (Instant) ta);

        // then — ZoneId 에 관계없이 clock.instant() 를 그대로 반환해야 한다
        assertThat(result)
                .as("clockDateTimeProvider() 는 Clock 의 ZoneId 와 무관하게 clock.instant() 를 반환해야 한다")
                .isPresent()
                .contains(fixedInstant);
    }

    /**
     * D4 단위 가드 — clockDateTimeProvider() 와 Spring 기본 CurrentDateTimeProvider 는 고정 Clock 상황에서 다른 값을 낸다.
     *
     * <p>이 테스트는 두 provider 가 서로 구분 가능함을 단정한다.
     * 고정 과거 Clock 을 주입하면 현재 시각을 반환하는 기본 provider 와 달라진다.
     */
    @Test
    @DisplayName("D4 설계 단정 — clockDateTimeProvider 는 고정 Clock 을 반영하므로 기본 CurrentDateTimeProvider 와 구별된다")
    void clockDateTimeProvider_distinguishableFromCurrentDateTimeProvider() throws Exception {
        // given — 고정 과거 시각 (현재 시각과 다름이 보장됨)
        Instant fixedPastInstant = Instant.parse("2000-01-01T00:00:00Z");
        Clock fixedPastClock = Clock.fixed(fixedPastInstant, ZoneOffset.UTC);
        JpaConfig jpaConfig = new JpaConfig(fixedPastClock);

        DateTimeProvider clockProvider = jpaConfig.clockDateTimeProvider();

        // Spring 기본 CurrentDateTimeProvider (dateTimeProviderRef 없을 때 쓰이는 provider)
        Class<?> currentDtpClass = Class.forName(
                "org.springframework.data.auditing.CurrentDateTimeProvider");
        Field instanceField = currentDtpClass.getDeclaredField("INSTANCE");
        instanceField.setAccessible(true);
        DateTimeProvider defaultProvider = (DateTimeProvider) instanceField.get(null);

        // when
        Instant fromClockProvider = (Instant) clockProvider.getNow().orElseThrow();
        // 기본 provider 는 LocalDateTime 을 반환하므로 Instant 로 변환하여 비교
        Instant fromDefaultProvider = defaultProvider.getNow()
                .map(ta -> {
                    if (ta instanceof java.time.LocalDateTime ldt) {
                        return ldt.toInstant(ZoneOffset.UTC);
                    }
                    return (Instant) ta;
                })
                .orElseThrow();

        // then — 고정 과거 시각이므로 기본 provider(현재 시각)와 달라야 한다
        assertThat(fromClockProvider)
                .as("clockDateTimeProvider 는 고정 과거 시각을 반환해야 한다 — 기본 provider 의 현재 시각과 달라야 함")
                .isBefore(fromDefaultProvider);
    }
}
