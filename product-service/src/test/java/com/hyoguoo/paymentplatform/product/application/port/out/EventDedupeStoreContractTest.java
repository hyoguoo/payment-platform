package com.hyoguoo.paymentplatform.product.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EventDedupeStore 포트 시그니처 컴파일 계약 테스트.
 *
 * <p>실제 삭제/기록 동작은 JdbcEventDedupeStoreCleanupTest / JdbcEventDedupeStoreRoundTripTest 에서 검증한다.
 * 이 테스트는 포트 인터페이스의 메서드 시그니처가 정확히 존재함을 컴파일 수준에서 강제한다
 * — 시그니처 드리프트 시 RED.
 */
@DisplayName("EventDedupeStore 포트 시그니처 컴파일 계약")
class EventDedupeStoreContractTest {

    /**
     * 포트 인터페이스의 recordIfAbsent 가 boolean recordIfAbsent(String, Instant, Instant) 시그니처임을
     * 컴파일 수준으로 단언.
     * 두 번째 인자 now 는 D1 결정 — 호출자(컨슈머)가 단일 시각을 산출해 주입한다.
     */
    @Test
    @DisplayName("recordIfAbsent — 포트 시그니처 boolean recordIfAbsent(String, Instant, Instant) 컴파일 계약")
    void recordIfAbsent_포트시그니처_now포함_컴파일계약() {
        EventDedupeStore store = new EventDedupeStore() {
            @Override
            public boolean recordIfAbsent(String eventUUID, Instant now, Instant expiresAt) {
                return false;
            }

            @Override
            public int deleteExpired(Instant now, int batchSize) {
                return 0;
            }
        };

        boolean result = store.recordIfAbsent("uuid", Instant.now(), Instant.now().plusSeconds(60));

        assertThat(result).isFalse();
    }

    /**
     * 포트 인터페이스의 deleteExpired 가 int deleteExpired(Instant, int) 시그니처임을 컴파일 수준으로 단언.
     * 익명 구현체를 통해 시그니처 계약을 강제한다.
     */
    @Test
    @DisplayName("deleteExpired — 포트 시그니처 int deleteExpired(Instant, int) 컴파일 계약")
    void deleteExpired_포트시그니처_컴파일계약() {
        EventDedupeStore store = new EventDedupeStore() {
            @Override
            public boolean recordIfAbsent(String eventUUID, Instant now, Instant expiresAt) {
                return false;
            }

            @Override
            public int deleteExpired(Instant now, int batchSize) {
                return 0;
            }
        };

        int result = store.deleteExpired(Instant.now(), 100);

        assertThat(result).isEqualTo(0);
    }
}
