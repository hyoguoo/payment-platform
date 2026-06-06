package com.hyoguoo.paymentplatform.payment.core.config;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * JPA 설정.
 *
 * <p>DM1 — auditing UTC 일원화:
 * Spring 기본 {@code CurrentDateTimeProvider} 는 JVM 기본 TZ 로 {@code LocalDateTime.now()} 를 반환한다.
 * 비-UTC JVM 에서는 KST wall-clock 값이 저장되어 UTC Instant cutoff 와 TZ 기준 불일치가 발생한다.
 * {@code clockDateTimeProvider} 빈이 {@code Clock} 을 통해 {@code Instant} 를 반환한다.
 * {@code @EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")} 로 연결.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "clockDateTimeProvider")
public class JpaConfig {

    private final Clock clock;

    @Autowired
    public JpaConfig(Clock clock) {
        this.clock = clock;
    }

    /**
     * Clock 기반 DateTimeProvider.
     *
     * <p>JPA auditing(@CreatedDate/@LastModifiedDate)이 이 빈을 통해 현재 시각을 얻는다.
     * {@code clock.instant()} 로 절대 시점({@link Instant})을 반환해
     * 비-UTC JVM TZ 에서도 auditing 시각이 UTC 기준 Instant 로 일관되게 저장된다.
     */
    @Bean
    public DateTimeProvider clockDateTimeProvider() {
        return () -> Optional.of(clock.instant());
    }

    /**
     * 명시적 JPA 트랜잭션 매니저.
     *
     * <p>{@code KafkaTransactionManager} 를 명시 등록하면
     * {@code KafkaTransactionManager} 가 {@code PlatformTransactionManager} 를 구현하기 때문에
     * Spring Boot JPA auto-config 의 {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)}
     * 조건이 충족되어 JPA {@code transactionManager} 빈 자동 생성이 억제됨.
     *
     * <p>결과: {@code @Transactional} 어노테이션 처리 시
     * "No bean named 'transactionManager' available" 오류 발생.
     *
     * <p>수정: {@code @Primary} JPA {@code transactionManager} 빈을 명시 등록.
     * {@code KafkaTransactionManager} 는 {@code kafkaTransactionManager} 이름으로 qualifier 분리됨.
     *
     * <p>이 빈은
     * {@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase#handle}
     * 에서 {@code @Transactional(transactionManager = "transactionManager")} qualifier 로 명시 참조된다.
     * Kafka {@code KafkaTransactionManager} 와의 TM 분리 원칙은
     * {@code PaymentConfirmResultUseCase} 클래스 주석 참고.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}


