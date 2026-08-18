package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 거절 전용 되돌리기(rejectCompensate) 호출 자체가 인프라 장애로 예외를 던지는 경우를 다룬다.
 * 상품 반복과 부분 실패 되돌리기는 이제 {@code PaymentTransactionCoordinator} 가 조립하지만,
 * 그 되돌리기가 최종적으로 부르는 이 어댑터 메서드가 예외를 삼키지 않는다는 것은 별도로 고정한다.
 *
 * <p>실제 Redis 장애를 재현하기 어려워 {@link StringRedisTemplate} 을 Mockito 로 대체한다 —
 * 이 프로젝트는 재고 보상 경로에서 예외를 삼켜 사고를 낸 이력이 있어, 삼키지 않고 그대로
 * 전파되는지를 별도로 고정한다.
 */
@DisplayName("StockCacheRedisAdapter — 거절 전용 되돌리기 예외 전파 테스트")
class StockCacheRedisAdapterRejectFailureTest {

    @Test
    @DisplayName("되돌리기 호출이 예외를 던지면 삼키지 않고 그대로 전파한다")
    void 되돌리기_예외는_삼키지_않고_전파된다() {
        // given
        StringRedisTemplate mockTemplate = mock(StringRedisTemplate.class);
        StockCacheRedisAdapter adapter = new StockCacheRedisAdapter(mockTemplate, 30);

        given(mockTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willThrow(new IllegalStateException("redis 연결 실패 — 되돌리기 도중 장애 재현"));

        PaymentOrder order = buildOrder(201L, 3);

        // when / then — 되돌리기 예외가 삼켜지지 않고 그대로 전파된다
        assertThatThrownBy(() -> adapter.rejectCompensate("order-reject-fail-001", order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis 연결 실패");
    }

    private PaymentOrder buildOrder(Long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .id(productId)
                .paymentEventId(1L)
                .orderId("order-test")
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
