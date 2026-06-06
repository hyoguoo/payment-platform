package com.hyoguoo.paymentplatform.payment.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.auditing.AuditingHandlerSupport;
import org.springframework.data.auditing.DateTimeProvider;

/**
 * DM1 회귀 가드 — {@code @EnableJpaAuditing(dateTimeProviderRef="clockDateTimeProvider")} 연결 단정.
 *
 * <p>JPA Auditing 이 {@code clockDateTimeProvider} 빈을 실제로 사용하는지 ApplicationContext 수준에서 검증한다.
 * 이 테스트는 {@code dateTimeProviderRef} 설정이 누락되거나 잘못된 빈 이름으로 변경되면 실패한다.
 *
 * <h2>회귀 시나리오</h2>
 * <ul>
 *   <li>{@code @EnableJpaAuditing} 에서 {@code dateTimeProviderRef} 를 제거하면
 *       {@code jpaAuditingHandler} 빈의 {@code dateTimeProvider} 필드가 기본
 *       {@code CurrentDateTimeProvider.INSTANCE} 로 남아 이 테스트가 실패한다.
 *   <li>{@code dateTimeProviderRef = "wrongBeanName"} 으로 바뀌면 ApplicationContext 구동 자체가
 *       실패하거나 handler 가 다른 빈으로 연결되어 이 테스트가 실패한다.
 *   <li>{@code clockDateTimeProvider()} 빈이 {@link JpaConfig} 에서 제거되면
 *       {@code ApplicationContext} 구동 실패 → 테스트 자체가 ERROR 로 표시된다.
 * </ul>
 *
 * <h2>설계 근거 (M3 finding 대응)</h2>
 * 2라운드 review Critic/Domain Expert 의 major M3: DM1 GREEN 커밋에서 회귀 가드가
 * UTC JVM 에서도 통과하여 {@code dateTimeProviderRef} 누락을 탐지하지 못한다.
 * 이 테스트는 ApplicationContext 의 {@code jpaAuditingHandler} 빈에서 reflection 으로
 * {@code dateTimeProvider} 필드를 꺼내 {@code clockDateTimeProvider} 빈과
 * 동일 인스턴스인지 단정함으로써 JVM TZ 와 무관하게 연결 회귀를 잡는다.
 */
@DisplayName("DM1 회귀 가드 — JPA Auditing dateTimeProvider 빈 연결 단정")
class JpaAuditingProviderWiringTest extends BaseIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * DM1 핵심 회귀 가드 — {@code jpaAuditingHandler} 빈의 dateTimeProvider 필드가
     * {@code clockDateTimeProvider} 빈과 동일 인스턴스인지 단정한다.
     *
     * <p>{@code @EnableJpaAuditing(dateTimeProviderRef="clockDateTimeProvider")} 가 올바르게
     * 설정된 경우, Spring 은 ApplicationContext 구동 시 {@code jpaAuditingHandler} 빈에
     * {@code clockDateTimeProvider} 를 setDateTimeProvider 로 주입한다.
     * {@code dateTimeProviderRef} 를 제거하면 기본 {@code CurrentDateTimeProvider.INSTANCE} 가
     * 남아 있어 {@code clockDateTimeProvider} 빈 인스턴스와 달라지므로 이 테스트가 실패한다.
     *
     * <p>Spring Data JPA 는 auditing handler 를 {@code "jpaAuditingHandler"} 라는 빈 이름으로
     * 인프라 빈 등록한다 ({@code JpaAuditingRegistrar.getAuditingHandlerBeanName()}).
     * 이 빈은 {@code IsNewAwareAuditingHandler extends AuditingHandler extends AuditingHandlerSupport}
     * 계층이며, {@code dateTimeProvider} private 필드는 {@link AuditingHandlerSupport} 에 있다.
     *
     * @throws Exception reflection 접근 실패 시 — Spring Data JPA 내부 구현 변경으로 필드명이 바뀐 경우
     */
    @Test
    @DisplayName("jpaAuditingHandler.dateTimeProvider 가 clockDateTimeProvider 빈과 동일 인스턴스다")
    void jpaAuditing_dateTimeProviderRef_isLinkedToClockDateTimeProvider() throws Exception {
        // given — ApplicationContext 에서 auditing handler 와 clockDateTimeProvider 빈을 꺼낸다
        // "jpaAuditingHandler" 는 JpaAuditingRegistrar.getAuditingHandlerBeanName() 가 반환하는 내부 빈 이름
        AuditingHandlerSupport auditingHandler =
                (AuditingHandlerSupport) applicationContext.getBean("jpaAuditingHandler");
        DateTimeProvider clockDateTimeProviderBean =
                applicationContext.getBean("clockDateTimeProvider", DateTimeProvider.class);

        // AuditingHandlerSupport.dateTimeProvider — private 필드를 reflection 으로 읽는다
        Field dtpField = AuditingHandlerSupport.class.getDeclaredField("dateTimeProvider");
        dtpField.setAccessible(true);
        DateTimeProvider handlerDateTimeProvider = (DateTimeProvider) dtpField.get(auditingHandler);

        // then — 두 인스턴스가 같아야 한다: dateTimeProviderRef 가 clockDateTimeProvider 를 가리켜야 함
        // dateTimeProviderRef 를 제거하면 handlerDateTimeProvider = CurrentDateTimeProvider.INSTANCE 로 바뀌어 FAIL
        assertThat(handlerDateTimeProvider)
                .as("""
                        DM1 회귀 가드: jpaAuditingHandler.dateTimeProvider 가 clockDateTimeProvider 빈이어야 한다.
                        이 단정이 실패하면 @EnableJpaAuditing(dateTimeProviderRef="clockDateTimeProvider") 연결이 끊긴 것이다.
                        JpaConfig.@EnableJpaAuditing 의 dateTimeProviderRef 설정을 확인하라.
                        """)
                .isSameAs(clockDateTimeProviderBean);
    }

    /**
     * D4 전환 가드 — {@code clockDateTimeProvider} 반환 타입이 {@code Instant} 임을 단정한다.
     *
     * <p>P12(clockDateTimeProvider 반환 타입 Instant 전환) 의 RED 게이트 역할.
     * 현재(P11 시점) {@code clockDateTimeProvider} 는 {@code LocalDateTime} 을 반환하므로
     * 이 테스트는 RED 상태이고, P12 구현 후 GREEN 이 된다.
     *
     * <p>P12 완료 후에도 이 테스트가 지속적으로 GREEN 이면 {@code Instant} 반환이 회귀 없이 유지됨을 보장한다.
     */
    @Test
    @DisplayName("clockDateTimeProvider.getNow() 가 Instant 타입을 반환한다 (D4 전환 가드)")
    void clockDateTimeProvider_반환타입이Instant_를_반환한다() {
        // given — ApplicationContext 에서 clockDateTimeProvider 빈을 꺼낸다
        DateTimeProvider provider =
                applicationContext.getBean("clockDateTimeProvider", DateTimeProvider.class);

        // when
        Optional<TemporalAccessor> nowOpt = provider.getNow();

        // then — getNow() 결과가 Instant 타입이어야 한다 (D4: LocalDateTime → Instant 전환)
        assertThat(nowOpt).isPresent();
        assertThat(nowOpt.get())
                .as("D4 전환 가드: clockDateTimeProvider 가 Instant 를 반환해야 한다. "
                        + "LocalDateTime 이 반환되면 P12(반환 타입 Instant 전환)가 미완료된 것이다.")
                .isInstanceOf(Instant.class);
    }
}
