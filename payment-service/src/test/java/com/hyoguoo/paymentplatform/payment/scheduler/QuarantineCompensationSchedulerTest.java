package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.QuarantineCompensationHandler;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("QuarantineCompensationScheduler 테스트")
@ExtendWith(MockitoExtension.class)
class QuarantineCompensationSchedulerTest {

    private static final String ORDER_ID_1 = "order-pending-001";
    private static final String ORDER_ID_2 = "order-pending-002";

    @InjectMocks
    private QuarantineCompensationScheduler scheduler;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private QuarantineCompensationHandler quarantineCompensationHandler;

    @Test
    @DisplayName("scan - quarantineCompensationPending=true 레코드 스캔 → retryStockRollback() 재시도")
    void scan_WhenPendingFlagTrue_ShouldRetryRedisIncr() {
        // given
        PaymentEvent pendingEvent1 = buildPendingEvent(ORDER_ID_1);
        PaymentEvent pendingEvent2 = buildPendingEvent(ORDER_ID_2);
        given(paymentEventRepository.findByQuarantineCompensationPendingTrue())
                .willReturn(List.of(pendingEvent1, pendingEvent2));

        // when
        scheduler.scan();

        // then: 각 pendingEvent에 대해 retryStockRollback() 호출
        then(quarantineCompensationHandler).should(times(1)).retryStockRollback(ORDER_ID_1);
        then(quarantineCompensationHandler).should(times(1)).retryStockRollback(ORDER_ID_2);
    }

    // ---- factory helpers ----

    private PaymentEvent buildPendingEvent(String orderId) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId(orderId)
                .productId(10L)
                .quantity(2)
                .totalAmount(BigDecimal.valueOf(2000))
                .allArgsBuild();

        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(orderId)
                .paymentKey("pk-001")
                .status(PaymentEventStatus.QUARANTINED)
                .retryCount(0)
                .paymentOrderList(List.of(order))
                .quarantineCompensationPending(true)
                .allArgsBuild();
    }
}
