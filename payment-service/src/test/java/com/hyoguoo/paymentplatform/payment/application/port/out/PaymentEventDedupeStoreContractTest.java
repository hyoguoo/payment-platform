package com.hyoguoo.paymentplatform.payment.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PaymentEventDedupeStore 포트 — deleteExpired 시그니처 컴파일 계약 테스트.
 *
 * <p>실제 삭제 동작은 JdbcPaymentEventDedupeStoreCleanupTest(C-2)에서 검증한다.
 * 이 테스트는 포트 인터페이스에 deleteExpired(Instant, int) 시그니처가 정확히 존재함을
 * 컴파일 수준에서 강제한다 — 시그니처 드리프트 시 RED.
 */
@DisplayName("PaymentEventDedupeStore 포트 deleteExpired 시그니처 계약")
class PaymentEventDedupeStoreContractTest {

    /**
     * 포트 인터페이스의 deleteExpired 가 int deleteExpired(Instant, int) 시그니처임을 컴파일 수준으로 단언.
     * 익명 구현체를 통해 시그니처 계약을 강제한다.
     */
    @Test
    @DisplayName("deleteExpired — 포트 시그니처 int deleteExpired(Instant, int) 컴파일 계약")
    void deleteExpired_포트시그니처_컴파일계약() {
        PaymentEventDedupeStore store = new PaymentEventDedupeStore() {
            @Override
            public int markIfAbsent(String eventUuid, long orderId, String status, Instant expiresAt) {
                return 0;
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
