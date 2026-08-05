package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * 발행 재시도 간격 조건부 갱신 쿼리의 WHERE 조건이 조용히 빠지는 회귀를 막는 구조 검증.
 *
 * <p>상태 조건만 걸거나 횟수 조건만 걸어도 단일 스레드 기능 테스트는 여전히 통과한다 —
 * 조건 하나가 빠져야만 드러나는 동시 선점 경합은 단일 스레드로 재현되지 않기 때문이다.
 * 이 테스트는 선언된 쿼리 문자열 자체에 두 조건이 모두 있는지를 고정한다.
 */
@DisplayName("JpaPaymentOutboxRepository — 발행 재시도 간격 조건부 갱신 계약")
class JpaPaymentOutboxRepositoryRetryDelayContractTest {

    @Test
    @DisplayName("조건부_갱신_쿼리에_상태와_횟수_조건이_모두_있다")
    void 조건부_갱신_쿼리에_상태와_횟수_조건이_모두_있다() throws NoSuchMethodException {
        // given
        Method method = JpaPaymentOutboxRepository.class.getMethod(
                "recordRetryDelay", String.class, int.class, int.class, LocalDateTime.class);

        // when
        Query query = method.getAnnotation(Query.class);

        // then
        assertThat(query).isNotNull();
        String upper = query.value().toUpperCase();
        assertThat(upper)
                .as("상태 조건이 없으면 발행이 끝난 행이나 선점된 행도 되돌아갈 수 있다")
                .contains("STATUS = 'PENDING'");
        assertThat(upper)
                .as("횟수 조건이 없으면 다른 워커가 이미 마친 기록을 다시 덮어쓸 수 있다")
                .contains("RETRYCOUNT = :EXPECTEDRETRYCOUNT");
    }
}
