package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 복제본 데이터소스({@code paymentReplicaDataSource}) 격리 계약 테스트.
 *
 * <p>돈 경로 판정 일곱이 복제본을 읽지 않는 것이 이 설계의 안전 근거다 — 복제본으로 가는
 * 조회가 폴링 어댑터 하나뿐임을 빈 의존 그래프로 못박아, 나중에 다른 저장소가 조용히
 * 복제본을 물게 되는 변경을 구조 계약으로 잡는다.
 *
 * <p>{@code payment.datasource.replica.enabled=true} 로 복제본 데이터소스를 기본과 별개
 * 빈으로 띄운 상태에서 돌린다 — 꺼진 상태에서는 두 빈이 같은 객체를 가리켜 격리가 깨져도
 * 의존 그래프가 갈라지지 않는다.
 */
@DisplayName("복제본 데이터소스 격리 계약 테스트")
class ReplicaDataSourceIsolationTest extends BaseIntegrationTest {

    private static final String REPLICA_DATA_SOURCE_BEAN_NAME = "paymentReplicaDataSource";
    private static final String DEFAULT_DATA_SOURCE_BEAN_NAME = "dataSource";

    private static final Set<String> ALLOWED_REPLICA_DEPENDENTS = Set.of(
            "paymentReplicaJdbcTemplate",
            "paymentStatusQueryJdbcAdapter"
    );

    @DynamicPropertySource
    static void replicaProperties(DynamicPropertyRegistry registry) {
        // 복제본 인프라(Task 7)가 아직 없어, 기본과 같은 컨테이너를 가리키되 별개 DataSource
        // 빈으로 등록되게만 한다 — 이 계약 테스트가 검증하는 건 빈 의존 그래프이지 실제
        // 복제 지연이 아니다.
        registry.add("payment.datasource.replica.enabled", () -> "true");
        registry.add("payment.datasource.replica.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("payment.datasource.replica.username", MYSQL_CONTAINER::getUsername);
        registry.add("payment.datasource.replica.password", MYSQL_CONTAINER::getPassword);
    }

    @Autowired
    private ConfigurableListableBeanFactory beanFactory;

    @Test
    @DisplayName("복제본 데이터소스를 주입받는 빈은 폴링 어댑터 하나뿐이다")
    void 복제본_데이터소스를_주입받는_빈은_폴링_어댑터_하나뿐이다() {
        // 데이터소스 → 질의 템플릿 → 어댑터, 딱 두 단계만 본다. 그 뒤(서비스 · 컨트롤러)는
        // PaymentStatusQueryPort 인터페이스로 어댑터를 호출하는 쪽이라 "복제본을 주입받는
        // 빈"이 아니라 "포트를 호출하는 빈"이다 — 전체 전이 폐쇄를 쓰면 폴링 호출 경로에
        // 있는 모든 상위 빈이 끌려 들어와 이 계약이 성립할 수 없다.
        Set<String> dependents = dependentsWithinDepth(REPLICA_DATA_SOURCE_BEAN_NAME, 2);

        assertThat(dependents)
                .as("paymentReplicaDataSource 의존 사슬은 질의 템플릿과 폴링 어댑터로만 이뤄져야 한다: " + dependents)
                .isSubsetOf(ALLOWED_REPLICA_DEPENDENTS);
        assertThat(dependents)
                .as("폴링 어댑터가 실제로 복제본 데이터소스를 물고 있어야 한다")
                .contains("paymentStatusQueryJdbcAdapter");
    }

    @Test
    @DisplayName("폴링 어댑터가 아닌 저장소는 기본 데이터소스를 쓴다")
    void 폴링_어댑터가_아닌_저장소는_기본_데이터소스를_쓴다() {
        Set<String> replicaDependents = transitiveDependentsOf(REPLICA_DATA_SOURCE_BEAN_NAME);

        assertThat(replicaDependents)
                .as("결제 이벤트 저장소 어댑터는 복제본 데이터소스 의존 사슬에 있으면 안 된다")
                .doesNotContain("paymentEventRepositoryImpl");
    }

    @Test
    @DisplayName("확정 멱등 판정 저장소는 기본 데이터소스의 NamedParameterJdbcTemplate 을 쓴다")
    void 확정_멱등_판정_저장소는_기본_데이터소스의_NamedParameterJdbcTemplate_을_쓴다() {
        Set<String> replicaDependents = transitiveDependentsOf(REPLICA_DATA_SOURCE_BEAN_NAME);
        Set<String> defaultDependents = transitiveDependentsOf(DEFAULT_DATA_SOURCE_BEAN_NAME);

        // 실제로 샜던 경로 — jdbcTemplate 명시 등록이 없으면 NamedParameterJdbcTemplate
        // 자동 설정이 단일 후보로 남은 paymentReplicaJdbcTemplate 을 골라, 이 저장소가
        // Qualifier 없이 타입으로만 주입받는 NamedParameterJdbcTemplate 이 조용히 복제본을
        // 물게 된다.
        assertThat(replicaDependents)
                .as("namedParameterJdbcTemplate/jdbcPaymentEventDedupeStore 는 복제본 의존 사슬에 있으면 안 된다")
                .doesNotContain("namedParameterJdbcTemplate", "jdbcPaymentEventDedupeStore");
        assertThat(defaultDependents)
                .as("namedParameterJdbcTemplate/jdbcPaymentEventDedupeStore 는 기본 데이터소스 의존 사슬에 있어야 한다")
                .contains("namedParameterJdbcTemplate", "jdbcPaymentEventDedupeStore");
    }

    /**
     * 주어진 빈을 직접 또는 간접으로 의존하는 빈 이름을 전부 모은다.
     * {@link ConfigurableListableBeanFactory#getDependentBeans(String)} 은 한 단계 의존만
     * 돌려주므로, 재귀로 사슬 전체를 훑는다.
     */
    private Set<String> transitiveDependentsOf(String beanName) {
        return dependentsWithinDepth(beanName, Integer.MAX_VALUE);
    }

    /**
     * {@code maxDepth} 단계까지만 의존 사슬을 훑는다. 특정 자원(데이터소스)을 실제로
     * 주입받는 빈만 골라내고 싶을 때, 그 자원을 다루는 포트를 호출만 하는 상위 빈까지
     * 끌려 들어오는 것을 막는다.
     */
    private Set<String> dependentsWithinDepth(String beanName, int maxDepth) {
        Set<String> visited = new HashSet<>();
        collectDependents(beanName, maxDepth, visited);
        return visited;
    }

    private void collectDependents(String beanName, int remainingDepth, Set<String> visited) {
        if (remainingDepth <= 0) {
            return;
        }
        for (String dependent : beanFactory.getDependentBeans(beanName)) {
            if (visited.add(dependent)) {
                collectDependents(dependent, remainingDepth - 1, visited);
            }
        }
    }
}
