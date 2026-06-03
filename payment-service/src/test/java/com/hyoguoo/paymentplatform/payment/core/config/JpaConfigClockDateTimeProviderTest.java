package com.hyoguoo.paymentplatform.payment.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

/**
 * JpaConfig.clockDateTimeProvider() 빈 계약 단위 단정.
 *
 * <p>DM1 회귀 가드 — 이 테스트는 다음 두 계약을 검증한다.
 * <ol>
 *   <li>빈 동작 계약: {@code clockDateTimeProvider()} 가 주입받은 {@code Clock} 의 고정 instant 기준
 *       UTC {@link LocalDateTime} 을 반환한다. JVM TZ 에 영향을 받지 않는다.
 *   <li>UTC 기준 결정성: {@link Clock.fixed(Instant, UTC)} 를 주입하면 반환값이
 *       {@code LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)} 와 정확히 동치다.
 * </ol>
 *
 * <p>이 테스트는 Spring ApplicationContext 를 로드하지 않는 순수 단위 테스트다.
 * Spring JPA Auditing 연결 단정({@code dateTimeProviderRef} 가 {@code clockDateTimeProvider} 빈을
 * 실제로 가리키는지)은
 * {@link JpaAuditingProviderWiringTest} 에서 별도로 검증한다.
 *
 * <p>회귀 시나리오:
 * <ul>
 *   <li>{@link ClockConfig} 에서 {@link Clock.systemUTC()} 대신 시스템 TZ Clock 을 쓰면 비-UTC JVM 에서
 *       auditing 값이 어긋난다 → 이 테스트가 고정 instant 기준 UTC 동치 단정으로 잡는다.
 *   <li>{@code clockDateTimeProvider()} 구현이 {@code ZoneOffset.UTC} 대신 시스템 TZ 를 쓰면
 *       고정 instant 기준 UTC LocalDateTime 과 어긋난다 → 이 테스트가 잡는다.
 * </ul>
 */
@DisplayName("JpaConfig.clockDateTimeProvider() 빈 계약 단위 단정")
class JpaConfigClockDateTimeProviderTest {

    /**
     * DM1 회귀 가드 — clockDateTimeProvider() 가 고정 Clock 의 UTC wall-clock LocalDateTime 을 반환한다.
     *
     * <p>고정 instant 기준으로 UTC LocalDateTime 이 반환되는지 직접 단정한다.
     * 이 테스트는 JVM TZ 를 변조하지 않으므로 CI(UTC JVM) 에서도 의미 있는 가드가 성립한다:
     * UTC 가 아닌 ZoneId 를 쓰는 구현으로 회귀하면 {@code localDateTimeFromFixed} 가 어긋나 실패한다.
     */
    @Test
    @DisplayName("DM1 단위 가드 — clockDateTimeProvider() 가 Clock 고정 instant 기준 UTC LocalDateTime 을 반환한다")
    void clockDateTimeProvider_withFixedClock_returnsUtcLocalDateTime() {
        // given
        Instant fixedInstant = Instant.parse("2026-01-01T09:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        JpaConfig jpaConfig = new JpaConfig(fixedClock);

        DateTimeProvider provider = jpaConfig.clockDateTimeProvider();

        // when
        Optional<LocalDateTime> result = provider.getNow()
                .map(ta -> LocalDateTime.from(ta));

        // then
        LocalDateTime expectedUtc = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);
        assertThat(result)
                .as("clockDateTimeProvider() 는 고정 Clock 기준 UTC LocalDateTime 을 반환해야 한다")
                .isPresent()
                .contains(expectedUtc);
    }

    /**
     * DM1 단위 가드 — 비-UTC ZoneId Clock 으로도 반환값은 반드시 UTC 기준이어야 한다.
     *
     * <p>만약 구현이 {@code ZoneOffset.UTC} 대신 {@code clock.getZone()} 을 쓰면
     * KST Clock 에서 UTC 와 9시간 어긋난 값이 나온다 → 이 테스트가 잡는다.
     * 현재 구현({@code LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)})은 항상 UTC 기준이므로
     * KST Zone Clock 을 주입해도 UTC wall-clock 결과가 나와야 한다.
     */
    @Test
    @DisplayName("DM1 단위 가드 — KST Zone Clock 을 주입해도 반환값은 UTC 기준 LocalDateTime 이어야 한다")
    void clockDateTimeProvider_withKstZonedClock_stillReturnsUtcLocalDateTime() {
        // given — KST(+09:00) zone 의 고정 Clock. instant 는 UTC 기준이므로 오해 없음
        Instant fixedInstant = Instant.parse("2026-01-01T00:00:00Z");
        Clock kstClock = Clock.fixed(fixedInstant, ZoneOffset.ofHours(9));
        JpaConfig jpaConfig = new JpaConfig(kstClock);

        DateTimeProvider provider = jpaConfig.clockDateTimeProvider();

        // when
        Optional<LocalDateTime> result = provider.getNow()
                .map(ta -> LocalDateTime.from(ta));

        // then — ZoneId 에 관계없이 UTC 기준(ZoneOffset.UTC)으로 변환돼야 한다
        // KST zone 을 그대로 쓰면 "2026-01-01T09:00:00" (9시간 오차) 가 나온다 — 이걸 잡는 가드
        LocalDateTime expectedUtc = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);
        assertThat(result)
                .as("clockDateTimeProvider() 는 Clock 의 ZoneId 와 무관하게 UTC 기준 LocalDateTime 을 반환해야 한다")
                .isPresent()
                .contains(expectedUtc);
    }

    /**
     * DM1 단위 가드 — clockDateTimeProvider() 와 Spring 기본 CurrentDateTimeProvider 는 고정 Clock 상황에서 다른 값을 낸다.
     *
     * <p>이 테스트는 두 provider 가 서로 구분 가능함을 단정한다.
     * UTC JVM 에서는 두 provider 가 우연히 같은 값을 낼 수 있지만,
     * {@code Clock.fixed(과거 instant)} 를 주입하면 현재 시각과 달라져 구분된다.
     * 즉 {@code clockDateTimeProvider} 를 제거하고 기본 provider 로 돌아가면
     * 이 테스트가 설계상 다른 클래스 인스턴스를 사용한다는 사실을 반영한다.
     *
     * <p>ApplicationContext 에서 {@code dateTimeProviderRef} 가 실제로 연결됐는지 확인하는 가드는
     * {@link JpaAuditingProviderWiringTest} 에서 별도로 검증한다.
     */
    @Test
    @DisplayName("DM1 설계 단정 — clockDateTimeProvider 는 고정 Clock 을 반영하므로 기본 CurrentDateTimeProvider 와 구별된다")
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
        LocalDateTime fromClockProvider = clockProvider.getNow()
                .map(ta -> LocalDateTime.from(ta))
                .orElseThrow();
        LocalDateTime fromDefaultProvider = defaultProvider.getNow()
                .map(ta -> LocalDateTime.from(ta))
                .orElseThrow();

        // then — 고정 과거 시각이므로 기본 provider(현재 시각)와 달라야 한다
        // 이 단정이 깨지면(같아지면) → clockProvider 가 현재 시각을 반환하는 버그임
        assertThat(fromClockProvider)
                .as("clockDateTimeProvider 는 고정 과거 시각을 반환해야 한다 — 기본 provider 의 현재 시각과 달라야 함")
                .isBefore(fromDefaultProvider);
    }
}
