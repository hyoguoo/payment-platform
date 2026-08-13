package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toss 벤더 처리 중 거부(IDEMPOTENT_REQUEST_PROCESSING) 코드 분류.
 *
 * <p>같은 멱등키로 아직 처리 중인 원 호출과 겹친 요청에 벤더가 돌려주는 코드 — 재시도 대상이
 * 아닌 전용 분류로 갈라야 겹친 호출이 시도횟수를 소모하지 않는다.
 */
@DisplayName("TossPaymentErrorCode 처리 중 거부 코드 분류")
class TossPaymentErrorCodeTest {

    @Test
    @DisplayName("처리중거부코드_전용분류로_판정")
    void 처리중거부코드_전용분류로_판정() {
        TossPaymentErrorCode code = TossPaymentErrorCode.of("IDEMPOTENT_REQUEST_PROCESSING");

        assertThat(code).isEqualTo(TossPaymentErrorCode.IDEMPOTENT_REQUEST_PROCESSING);
        assertThat(code.isConcurrentCall()).isTrue();
    }

    @Test
    @DisplayName("처리중거부코드는_재시도대상이_아니다")
    void 처리중거부코드는_재시도대상이_아니다() {
        assertThat(TossPaymentErrorCode.IDEMPOTENT_REQUEST_PROCESSING.isRetryableError()).isFalse();
    }
}
