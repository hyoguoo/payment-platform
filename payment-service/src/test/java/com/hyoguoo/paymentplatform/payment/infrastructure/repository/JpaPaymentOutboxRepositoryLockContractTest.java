package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * 확인 조회의 잠금 방식이 조용히 바뀌는 회귀를 막는 구조 검증.
 * 잠금 선언을 제거하거나 워커 선점용 건너뛰기 힌트로 바꾸면 이 테스트가 실패한다.
 */
@DisplayName("JpaPaymentOutboxRepository — 확인 조회 잠금 계약")
class JpaPaymentOutboxRepositoryLockContractTest {

    @Test
    @DisplayName("확인_조회는_쓰기_잠금_읽기로_선언된다")
    void 확인_조회는_쓰기_잠금_읽기로_선언된다() throws NoSuchMethodException {
        // given
        Method method = JpaPaymentOutboxRepository.class.getMethod("findByOrderIdForUpdate", String.class);

        // when
        Lock lock = method.getAnnotation(Lock.class);

        // then
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    @DisplayName("확인_조회에_건너뛰기_힌트가_없다")
    void 확인_조회에_건너뛰기_힌트가_없다() throws NoSuchMethodException {
        // given
        Method method = JpaPaymentOutboxRepository.class.getMethod("findByOrderIdForUpdate", String.class);

        // when
        Query query = method.getAnnotation(Query.class);

        // then
        assertThat(query).isNotNull();
        assertThat(query.value().toUpperCase()).doesNotContain("SKIP LOCKED");
    }
}
