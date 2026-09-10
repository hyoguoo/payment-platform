package com.hyoguoo.paymentplatform.payment.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FakePaymentEventRepository 조회가 저장소 참조를 그대로 돌려주지 않고 방어적으로 복사하는지 검증.
 * 조회 결과를 호출자가 자유롭게 뮤테이트해도(도메인 전이 메서드 포함) 저장소 상태는 영향받지 않아야
 * 한다 — 영향받으면 {@code resolve*} 계열 CAS 검사가 이미 뮤테이트된 값을 보게 되어 정상 케이스도
 * 항상 충돌로 오판한다.
 */
@DisplayName("FakePaymentEventRepository")
class FakePaymentEventRepositoryTest {

    private static final String ORDER_ID = "order-copy-001";

    @Test
    @DisplayName("findByOrderId 반환 객체를 변경해도 저장소 상태가 유지된다")
    void findByOrderId_반환객체를_변경해도_저장소_상태가_유지된다() {
        FakePaymentEventRepository repository = new FakePaymentEventRepository();
        PaymentEvent event = buildPaymentEvent(1L, PaymentEventStatus.IN_PROGRESS, List.of());
        repository.save(event);

        PaymentEvent fetched = repository.findByOrderId(ORDER_ID).orElseThrow();
        fetched.quarantine("임의_사유", Instant.parse("2026-04-27T12:00:00Z"));

        PaymentEvent reFetched = repository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(reFetched.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("findById 반환 객체의 자식 주문 리스트가 저장소와 분리된다")
    void findById_반환객체의_자식주문_리스트가_저장소와_분리된다() {
        FakePaymentEventRepository repository = new FakePaymentEventRepository();
        PaymentOrder order = buildPaymentOrder();
        PaymentEvent event = buildPaymentEvent(2L, PaymentEventStatus.IN_PROGRESS, List.of(order));
        repository.save(event);

        PaymentEvent fetched = repository.findById(2L).orElseThrow();
        fetched.getPaymentOrderList().clear();

        PaymentEvent reFetched = repository.findById(2L).orElseThrow();
        assertThat(reFetched.getPaymentOrderList()).hasSize(1);
    }

    private PaymentEvent buildPaymentEvent(Long id, PaymentEventStatus status, List<PaymentOrder> orders) {
        return PaymentEvent.allArgsBuilder()
                .id(id)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .status(status)
                .paymentOrderList(orders)
                .allArgsBuild();
    }

    private PaymentOrder buildPaymentOrder() {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(2L)
                .orderId(ORDER_ID)
                .productId(10L)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(1000))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
