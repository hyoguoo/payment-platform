package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * payment DB 데이터소스 설정. 기본 데이터소스({@code dataSource})에 더해 폴링 조회 전용
 * 읽기 복제본({@code paymentReplicaDataSource})을 함께 정의한다.
 *
 * <p>기본 데이터소스도 이 설정에서 직접 등록하는 이유 — Spring Boot
 * {@code DataSourceAutoConfiguration} 은 컨텍스트에 {@link DataSource} 타입 빈이 하나라도
 * 이미 있으면({@code @ConditionalOnMissingBean(DataSource.class)}) 자체 기본 빈 생성을
 * 통째로 건너뛴다. 복제본 빈만 추가하면 그 조건에 걸려 기본 데이터소스 자동 생성이
 * 사라지므로, 같은 {@code spring.datasource.*} 프로퍼티로 기본 빈을 명시 등록해 접속
 * 정보·Hikari 풀 설정 등 외부에서 보이는 동작은 그대로 유지한다.
 *
 * <p>기본 데이터소스에 {@code @Primary} 를 붙이는 이유 — JPA 자동 설정
 * ({@code JpaBaseConfiguration})은 {@code @ConditionalOnSingleCandidate(DataSource.class)}
 * 로 단일 후보이거나 {@code @Primary} 로 지목된 빈을 요구한다. 복제본 빈이 항상 두 번째
 * {@link DataSource} 후보로 존재하는 이상 이 표시가 없으면 JPA 자동 설정 자체가 꺼져
 * EntityManager 를 아무도 못 받는다. 복제본 빈에는 붙이지 않는다 — 이름·Qualifier 없는
 * 자리는 항상 이 기본 데이터소스를 받는다.
 *
 * <p>{@code payment.datasource.replica.enabled} 가 거짓이면 복제본 빈이 기본 데이터소스를
 * 그대로 반환해, 복제본 인프라 없이도 같은 이름의 빈이 항상 존재한다. 폴링 조회 어댑터
 * ({@code PaymentStatusQueryJdbcAdapter})만 이 빈을 주입받는다 — 돈 경로 판정은 여전히
 * 기본 데이터소스를 읽는다.
 *
 * <p>기본 데이터소스용 {@code jdbcTemplate} 빈을 이 클래스에서 직접 등록하고 {@code @Primary}
 * 를 붙이는 이유 — Spring Boot {@code JdbcTemplateAutoConfiguration} 은
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)} 로 자체 기본 {@code JdbcTemplate}
 * 생성을 건너뛴다. 복제본 전용 {@code paymentReplicaJdbcTemplate} 도 {@link JdbcOperations}
 * 타입이라 이 조건에 걸려 자동 등록이 사라지고, 뒤이어 {@code NamedParameterJdbcTemplate}
 * 자동 설정({@code @ConditionalOnSingleCandidate(JdbcTemplate.class)})이 유일하게 남은
 * 복제본 템플릿을 단일 후보로 골라버린다 — {@code @Qualifier} 없이 타입으로만 주입받는
 * {@code JdbcPaymentEventDedupeStore}(돈 경로 확정 멱등 판정)가 조용히 복제본을 읽게
 * 되는 것을 통합 테스트로 확인했다. 기본 데이터소스용 템플릿을 명시 등록하고 {@code @Primary}
 * 로 지목해 단일 후보 조건이 항상 기본 데이터소스 쪽으로 수렴하게 만든다.
 */
@Slf4j
@Configuration
public class ReplicaDataSourceConfig {

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * 기본 데이터소스({@code dataSource}) 전용 {@link JdbcTemplate}. {@code @Primary} 로 지목해
     * {@code NamedParameterJdbcTemplate} 자동 설정의 단일 후보 판정이 항상 이 빈으로 수렴하게
     * 만든다 — 클래스 Javadoc "복제본 전용 JdbcTemplate 이 자동 설정을 밀어내는 문제" 참고.
     */
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "paymentReplicaDataSource")
    public DataSource paymentReplicaDataSource(
            @Value("${payment.datasource.replica.enabled:false}") boolean replicaEnabled,
            @Value("${payment.datasource.replica.url:}") String replicaUrl,
            @Value("${payment.datasource.replica.username:}") String replicaUsername,
            @Value("${payment.datasource.replica.password:}") String replicaPassword,
            @Qualifier("dataSource") DataSource dataSource
    ) {
        if (!replicaEnabled) {
            log.info("payment.datasource.replica.enabled=false — 폴링 조회가 기본 데이터소스를 그대로 사용합니다.");
            return dataSource;
        }

        log.info("payment.datasource.replica.enabled=true — 폴링 조회가 복제본 데이터소스를 사용합니다.");
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(replicaUrl)
                .username(replicaUsername)
                .password(replicaPassword)
                .build();
    }

    /**
     * 폴링 조회 어댑터({@code PaymentStatusQueryJdbcAdapter}) 전용 {@link JdbcTemplate}.
     *
     * <p>기본 데이터소스는 위 {@code jdbcTemplate} 빈이 맡는다. 복제본을 읽으려면
     * {@code paymentReplicaDataSource} 로 감싼 별도 빈이 필요해 이 자리에서 명시 이름으로
     * 등록한다 — 복제본으로 가는 조회가 폴링 어댑터 하나뿐임을 빈 이름으로도 드러낸다.
     */
    @Bean(name = "paymentReplicaJdbcTemplate")
    public JdbcTemplate paymentReplicaJdbcTemplate(
            @Qualifier("paymentReplicaDataSource") DataSource paymentReplicaDataSource) {
        return new JdbcTemplate(paymentReplicaDataSource);
    }
}
