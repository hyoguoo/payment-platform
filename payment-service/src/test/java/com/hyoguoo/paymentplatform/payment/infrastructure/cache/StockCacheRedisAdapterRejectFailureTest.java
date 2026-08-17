package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 상품 반복 중 재고 부족을 만나 앞서 차감한 상품을 거절 전용 되돌리기로 되돌리는 도중,
 * 되돌리기 호출 자체가 인프라 장애로 예외를 던지는 경우를 다룬다.
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
        // given — 상품A 는 차감 성공, 상품B 는 재고 부족 → 상품A 를 되돌리는 시점에 인프라 장애
        StringRedisTemplate mockTemplate = mock(StringRedisTemplate.class);
        StockCacheRedisAdapter adapter = new StockCacheRedisAdapter(mockTemplate);

        given(mockTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (keys.size() == 2) {
                        // 단일 상품 차감 스크립트 호출 — 마지막 키(재고 키)로 상품 구분
                        String stockKey = keys.get(1);
                        return stockKey.contains("{201}") ? "OK" : "INSUFFICIENT";
                    }
                    // KEYS 3개 = 거절 전용 되돌리기 호출 — 인프라 장애 재현
                    throw new IllegalStateException("redis 연결 실패 — 되돌리기 도중 장애 재현");
                });

        Long productId1 = 201L;
        Long productId2 = 202L;
        List<PaymentOrder> orders = List.of(
                buildOrder(productId1, 3),
                buildOrder(productId2, 5)
        );

        // when / then — 되돌리기 예외가 삼켜지지 않고 그대로 전파된다
        assertThatThrownBy(() -> adapter.decrementAtomic("order-reject-fail-001", orders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis 연결 실패");
    }

    @Test
    @DisplayName("첫 상품부터 부족하면 되돌릴 것이 없어 되돌리기를 호출하지 않는다")
    void 첫_상품부터_부족하면_되돌리기_호출_없음() {
        // given
        StringRedisTemplate mockTemplate = mock(StringRedisTemplate.class);
        StockCacheRedisAdapter adapter = new StockCacheRedisAdapter(mockTemplate);

        given(mockTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (keys.size() == 2) {
                        return "INSUFFICIENT";
                    }
                    throw new IllegalStateException("되돌릴 것이 없는데 되돌리기가 호출됨");
                });

        List<PaymentOrder> orders = List.of(buildOrder(203L, 5));

        // when
        StockDecrementAtomicResult result = adapter.decrementAtomic("order-reject-fail-002", orders);

        // then — 되돌리기 호출 없이 INSUFFICIENT 만 반환
        assertThat(result).isEqualTo(StockDecrementAtomicResult.INSUFFICIENT);
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
