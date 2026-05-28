package com.hyoguoo.paymentplatform.payment.core.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableJpaAuditing
public class JpaConfig {

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


