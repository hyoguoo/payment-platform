package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentEventTest {

    static PaymentEvent defaultPaymentEvent() {
        PaymentOrder paymentOrder1 = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .quantity(1)
                .totalAmount(new BigDecimal("5000"))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();

        PaymentOrder paymentOrder2 = PaymentOrder.allArgsBuilder()
                .id(2L)
                .quantity(1)
                .totalAmount(new BigDecimal("10000"))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();

        return PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order123")
                .paymentKey("validPaymentKey")
                .status(PaymentEventStatus.IN_PROGRESS)
                .retryCount(0)
                .paymentOrderList(List.of(paymentOrder1, paymentOrder2))
                .allArgsBuild();
    }

    private PaymentEvent defaultExecutedPaymentEventWithStatus(
            PaymentEventStatus paymentEventStatus,
            PaymentOrderStatus paymentOrderStatus
    ) {
        PaymentOrder paymentOrder1 = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .quantity(1)
                .totalAmount(new BigDecimal(5000))
                .status(paymentOrderStatus)
                .allArgsBuild();

        PaymentOrder paymentOrder2 = PaymentOrder.allArgsBuilder()
                .id(2L)
                .quantity(1)
                .totalAmount(new BigDecimal(10000))
                .status(paymentOrderStatus)
                .allArgsBuild();

        return PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order123")
                .paymentKey("validPaymentKey")
                .status(paymentEventStatus)
                .retryCount(0)
                .approvedAt(LocalDateTime.of(2021, 1, 1, 0, 0, 0))
                .paymentOrderList(List.of(paymentOrder1, paymentOrder2))
                .allArgsBuild();
    }

    @Test
    @DisplayName("allArgs Builder를 사용하여 PaymentEvent 객체를 생성한다.")
    void testAllArgsConstructor() {
        // given
        Long id = 1L;
        Long buyerId = 100L;
        Long sellerId = 200L;
        String orderName = "Test Order";
        String orderId = "order123";
        String paymentKey = "validPaymentKey";
        PaymentEventStatus status = PaymentEventStatus.IN_PROGRESS;
        LocalDateTime approvedAt = LocalDateTime.now();
        LocalDateTime executedAt = LocalDateTime.now();
        Integer retryCount = 1;
        List<PaymentOrder> paymentOrderList = List.of(
                PaymentOrder.allArgsBuilder()
                        .id(1L)
                        .paymentEventId(1L)
                        .quantity(1)
                        .totalAmount(new BigDecimal("10000"))
                        .status(PaymentOrderStatus.NOT_STARTED)
                        .allArgsBuild(),
                PaymentOrder.allArgsBuilder()
                        .id(2L)
                        .quantity(2)
                        .totalAmount(new BigDecimal("20000"))
                        .status(PaymentOrderStatus.NOT_STARTED)
                        .allArgsBuild()
        );

        // when
        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(id)
                .buyerId(buyerId)
                .sellerId(sellerId)
                .orderName(orderName)
                .orderId(orderId)
                .paymentKey(paymentKey)
                .status(status)
                .approvedAt(approvedAt)
                .executedAt(executedAt)
                .retryCount(retryCount)
                .paymentOrderList(paymentOrderList)
                .allArgsBuild();

        // then
        Assertions.assertThat(paymentEvent)
                .extracting(PaymentEvent::getId,
                        PaymentEvent::getBuyerId,
                        PaymentEvent::getSellerId,
                        PaymentEvent::getOrderName,
                        PaymentEvent::getOrderId,
                        PaymentEvent::getPaymentKey,
                        PaymentEvent::getStatus,
                        PaymentEvent::getApprovedAt,
                        PaymentEvent::getExecutedAt,
                        PaymentEvent::getRetryCount,
                        PaymentEvent::getPaymentOrderList)
                .containsExactly(id, buyerId, sellerId, orderName, orderId, paymentKey,
                        status, approvedAt, executedAt, retryCount, paymentOrderList);
    }

    @Test
    @DisplayName("required Builder를 사용하여 객체를 생성 시 올바른 상태로 생성된다.")
    void testRequiredConstructor() {
        // given
        UserInfo userInfo = UserInfo.builder()
                .id(1L)
                .build();

        ProductInfo productInfo1 = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(new BigDecimal("5000"))
                .stock(100)
                .sellerId(2L)
                .build();

        ProductInfo productInfo2 = ProductInfo.builder()
                .id(2L)
                .name("Product 2")
                .price(new BigDecimal("10000"))
                .stock(50)
                .sellerId(2L)
                .build();

        List<ProductInfo> productInfoList = List.of(productInfo1, productInfo2);

        String orderId = "order123";

        // when
        PaymentEvent paymentEvent = PaymentEvent.create(
                userInfo,
                productInfoList,
                orderId,
                LocalDateTime.now(),
                PaymentGatewayType.TOSS
        );

        // then
        assertThat(paymentEvent.getOrderName()).isEqualTo("Product 1 포함 2건");
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.READY);
        assertThat(paymentEvent.getApprovedAt()).isNull();
        assertThat(paymentEvent.getPaymentOrderList()).isEmpty();
        assertThat(paymentEvent.getPaymentKey()).isNull();
        assertThat(paymentEvent.getExecutedAt()).isNull();
        assertThat(paymentEvent.getRetryCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS"})
    @DisplayName("결제 시작 시 특정 상태에서 성공적으로 IN_PROGRESS 상태로 변경하고, 실행 시간을 설정한다.")
    void execute_Success(PaymentEventStatus paymentEventStatus) {
        // given
        LocalDateTime executedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.NOT_STARTED
        );

        // when
        paymentEvent.execute("validPaymentKey", executedAt, LocalDateTime.now());

        // then
        assertThat(paymentEvent.getPaymentKey()).isEqualTo("validPaymentKey");
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
        assertThat(paymentEvent.getExecutedAt()).isEqualTo(executedAt);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "RETRYING"})
    @DisplayName("결제 시작 시  in progress 상태로 변경 불가한 상태에서는 에외를 던진다.")
    void execute_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        LocalDateTime executedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.NOT_STARTED
        );

        // when & then
        assertThatThrownBy(() -> paymentEvent.execute("validPaymentKey", executedAt, LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"IN_PROGRESS", "RETRYING", "DONE"})
    @DisplayName("결제 완료 시 특정 상태에서 성공적으로 done 상태로 변경한다.")
    void done_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        LocalDateTime approvedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        // when
        paymentEvent.done(approvedAt, LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.DONE);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"IN_PROGRESS", "RETRYING", "DONE"})
    @DisplayName("done() 호출 시 approvedAt non-null이면 status가 DONE이 되고 approvedAt이 저장된다.")
    void done_WithApprovedAt_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );
        LocalDateTime approvedAt = LocalDateTime.of(2024, 6, 1, 12, 0, 0);

        // when
        paymentEvent.done(approvedAt, LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.DONE);
        assertThat(paymentEvent.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    @DisplayName("done() 호출 시 approvedAt이 null이면 MISSING_APPROVED_AT 코드로 PaymentStatusException을 던진다.")
    void done_NullApprovedAt_ThrowsPaymentStatusException() {
        // given — 허용 source(IN_PROGRESS/RETRYING/DONE) 중 IN_PROGRESS 대표 사용
        // DONE source + null approvedAt 조합은 allArgsBuilder로만 생성 가능한 시나리오이므로
        // domain invariant 검증 목적으로 세 가지 source 모두 확인
        for (PaymentEventStatus source : List.of(
                PaymentEventStatus.IN_PROGRESS,
                PaymentEventStatus.RETRYING,
                PaymentEventStatus.DONE)) {
            PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                    source,
                    PaymentOrderStatus.EXECUTING
            );

            // when & then
            assertThatThrownBy(() -> paymentEvent.done(null, LocalDateTime.now()))
                    .isInstanceOf(PaymentStatusException.class)
                    .satisfies(ex -> {
                        PaymentStatusException statusEx = (PaymentStatusException) ex;
                        assertThat(statusEx.getCode()).isEqualTo(PaymentErrorCode.MISSING_APPROVED_AT.getCode());
                    });
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED"})
    @DisplayName("결제 완료 시 done 상태로 변경 불가한 상태에서는 예외를 던진다. (RETRYING은 허용)")
    void done_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        LocalDateTime approvedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

        // when & then
        assertThatThrownBy(() -> paymentEvent.done(approvedAt, LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING"})
    @DisplayName("결제 실패 시 비종결 상태에서 성공적으로 FAILED 상태로 변경하고 statusReason이 저장된다.")
    void fail_ValidSource_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );
        String reason = "test failure reason";

        // when
        paymentEvent.fail(reason, LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
        assertThat(paymentEvent.getStatusReason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING"})
    @DisplayName("결제 실패 시 특정 상태에서 성공적으로 fail 상태로 변경한다.")
    void fail_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when
        paymentEvent.fail("test failure reason", LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"FAILED", "DONE", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    @DisplayName("종결 상태에서 fail() 호출 시 no-op — status 변경 없음, 예외 없음.")
    void fail_AlreadyTerminalStatus_NoOp(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );
        PaymentEventStatus statusBefore = paymentEvent.getStatus();

        // when & then — 예외 없이 완료되어야 하며 status가 변경되지 않아야 한다
        paymentEvent.fail("attempt to fail terminal", LocalDateTime.now());
        assertThat(paymentEvent.getStatus()).isEqualTo(statusBefore);
    }

    @ParameterizedTest
    @CsvSource({
            "5000, 2, 10000, 1, 20000",
            "2000, 1, 3000, 2, 8000",
            "0, 1, 0, 1, 0",
            "9999, 3, 1, 3, 30000"
    })
    @DisplayName("주문 목록의 총 금액을 반환한다.")
    void testGetTotalAmount(
            String amount1,
            int quantity1,
            String amount2,
            int quantity2,
            String expectedTotal
    ) {
        // given
        PaymentOrder paymentOrder1 = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .quantity(quantity1)
                .totalAmount(new BigDecimal(amount1).multiply(new BigDecimal(quantity1)))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();

        PaymentOrder paymentOrder2 = PaymentOrder.allArgsBuilder()
                .id(2L)
                .quantity(quantity2)
                .totalAmount(new BigDecimal(amount2).multiply(new BigDecimal(quantity2)))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order123")
                .paymentKey("validPaymentKey")
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(List.of(paymentOrder1, paymentOrder2))
                .allArgsBuild();

        // when
        BigDecimal totalAmount = paymentEvent.getTotalAmount();

        // then
        assertThat(totalAmount).isEqualTo(new BigDecimal(expectedTotal));
    }

    @Test
    @DisplayName("READY 상태의 PaymentEvent를 EXPIRED 상태로 변경할 수 있다.")
    void expire_Success() {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                PaymentEventStatus.READY,
                PaymentOrderStatus.NOT_STARTED
        );

        // when
        paymentEvent.expire(LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.EXPIRED);
        paymentEvent.getPaymentOrderList().forEach(order ->
                assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.EXPIRED)
        );
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"IN_PROGRESS", "RETRYING", "DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED"})
    @DisplayName("READY 상태가 아닌 PaymentEvent는 EXPIRED 상태로 변경할 수 없다.")
    void expire_InvalidStatus_ThrowsException(PaymentEventStatus invalidStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                invalidStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when & then
        assertThatThrownBy(() -> paymentEvent.expire(LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    @Test
    @DisplayName("requiredBuilder로 생성 시 lastStatusChangedAt 필드가 초기화된다.")
    void lastStatusChangedAt_InitializedOnCreation() {
        // given
        UserInfo userInfo = UserInfo.builder().id(1L).build();
        ProductInfo productInfo = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(new BigDecimal("5000"))
                .stock(100)
                .sellerId(2L)
                .build();
        LocalDateTime creationTime = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

        // when
        PaymentEvent paymentEvent = PaymentEvent.create(
                userInfo,
                List.of(productInfo),
                "order123",
                creationTime,
                PaymentGatewayType.TOSS
        );

        // then
        assertThat(paymentEvent.getLastStatusChangedAt()).isEqualTo(creationTime);
    }

    @Test
    @DisplayName("상태 변경 시 lastStatusChangedAt 필드가 업데이트된다.")
    void lastStatusChangedAt_UpdatedOnStatusChange() {
        // given
        LocalDateTime initialTime = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        LocalDateTime statusChangeTime = LocalDateTime.of(2021, 1, 1, 0, 5, 0);

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order123")
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .lastStatusChangedAt(initialTime)
                .paymentOrderList(List.of())
                .allArgsBuild();

        // when
        paymentEvent.execute("validPaymentKey", statusChangeTime, statusChangeTime);

        // then
        assertThat(paymentEvent.getLastStatusChangedAt()).isEqualTo(statusChangeTime);
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("상태 변경 간의 Duration을 계산할 수 있다.")
    void lastStatusChangedAt_DurationCalculation() {
        // given
        LocalDateTime initialTime = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        LocalDateTime statusChangeTime = LocalDateTime.of(2021, 1, 1, 0, 5, 30);

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order123")
                .status(PaymentEventStatus.IN_PROGRESS)
                .retryCount(0)
                .lastStatusChangedAt(initialTime)
                .paymentOrderList(List.of())
                .allArgsBuild();

        // when
        Duration duration = Duration.between(paymentEvent.getLastStatusChangedAt(), statusChangeTime);

        // then
        assertThat(duration.toMinutes()).isEqualTo(5);
        assertThat(duration.getSeconds()).isEqualTo(330); // 5분 30초
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "EXPIRED"})
    @DisplayName("각 상태 변경 메서드 호출 시 lastStatusChangedAt이 업데이트된다.")
    void lastStatusChangedAt_UpdatedOnEachStatusChange(PaymentEventStatus targetStatus) {
        // given
        LocalDateTime initialTime = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        LocalDateTime statusChangeTime = LocalDateTime.of(2021, 1, 1, 0, 10, 0);

        PaymentEventStatus initialStatus = switch (targetStatus) {
            case DONE, FAILED -> PaymentEventStatus.IN_PROGRESS;
            case EXPIRED -> PaymentEventStatus.READY;
            default -> PaymentEventStatus.READY;
        };

        PaymentOrderStatus paymentOrderStatus = switch (targetStatus) {
            case DONE, FAILED -> PaymentOrderStatus.EXECUTING;
            case EXPIRED -> PaymentOrderStatus.NOT_STARTED;
            default -> PaymentOrderStatus.NOT_STARTED;
        };

        PaymentOrder paymentOrder = PaymentOrder.allArgsBuilder()
                .id(1L)
                .quantity(1)
                .totalAmount(new BigDecimal("5000"))
                .status(paymentOrderStatus)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("테스트 주문")
                .orderId("order123")
                .status(initialStatus)
                .retryCount(0)
                .lastStatusChangedAt(initialTime)
                .paymentOrderList(List.of(paymentOrder))
                .allArgsBuild();

        // when
        switch (targetStatus) {
            case DONE -> paymentEvent.done(statusChangeTime, statusChangeTime);
            case FAILED -> paymentEvent.fail("test reason", statusChangeTime);
            case EXPIRED -> paymentEvent.expire(statusChangeTime);
            default -> throw new AssertionError("Unexpected status: " + targetStatus);
        }

        // then
        assertThat(paymentEvent.getLastStatusChangedAt()).isEqualTo(statusChangeTime);
        assertThat(paymentEvent.getStatus()).isEqualTo(targetStatus);
    }

    @Test
    @DisplayName("validateConfirmRequest - 유효한 요청: 예외가 발생하지 않는다")
    void validateConfirmRequest_valid_noException() {
        PaymentEvent paymentEvent = defaultPaymentEvent();
        // totalAmount = 5000 + 10000 = 15000
        paymentEvent.validateConfirmRequest(1L, new BigDecimal("15000"), "order123", "validPaymentKey");
    }

    @Test
    @DisplayName("validateConfirmRequest - paymentKey null: 예외가 발생하지 않는다")
    void validateConfirmRequest_nullPaymentKey_noException() {
        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .orderId("order123")
                .paymentKey(null)
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .paymentOrderList(List.of(
                        PaymentOrder.allArgsBuilder()
                                .quantity(1)
                                .totalAmount(new BigDecimal("15000"))
                                .status(PaymentOrderStatus.NOT_STARTED)
                                .allArgsBuild()))
                .allArgsBuild();

        paymentEvent.validateConfirmRequest(1L, new BigDecimal("15000"), "order123", "anyKey");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"IN_PROGRESS", "RETRYING"})
    @DisplayName("재시도 전환 시 IN_PROGRESS/RETRYING 상태에서 RETRYING 상태로 변경된다.")
    void toRetrying_성공(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus, PaymentOrderStatus.EXECUTING);

        // when
        paymentEvent.toRetrying(LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.RETRYING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED"})
    @DisplayName("재시도 전환 시 허용되지 않는 상태(QUARANTINED 제외 종결 상태)에서는 예외를 던진다.")
    void toRetrying_실패(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus, PaymentOrderStatus.EXECUTING);

        // when & then
        assertThatThrownBy(() -> paymentEvent.toRetrying(LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    @Test
    @DisplayName("READY 상태에서 toRetrying() 호출 시 RETRYING으로 전환되고 retryCount가 1이 된다.")
    void toRetrying_FromReady_Success() {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                PaymentEventStatus.READY, PaymentOrderStatus.NOT_STARTED);

        // when
        paymentEvent.toRetrying(LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.RETRYING);
        assertThat(paymentEvent.getRetryCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    @DisplayName("종결 상태(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED)에서 toRetrying() 호출 시 PaymentStatusException을 던진다.")
    void toRetrying_TerminalSource_Throws(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus, PaymentOrderStatus.EXECUTING);

        // when & then
        assertThatThrownBy(() -> paymentEvent.toRetrying(LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    @Test
    @DisplayName("재시도 전환 시 retryCount가 1 증가한다.")
    void toRetrying_호출_시_retryCount_증가() {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                PaymentEventStatus.IN_PROGRESS, PaymentOrderStatus.EXECUTING);
        int initialRetryCount = paymentEvent.getRetryCount();

        // when
        paymentEvent.toRetrying(LocalDateTime.now());

        // then
        assertThat(paymentEvent.getRetryCount()).isEqualTo(initialRetryCount + 1);
    }

    @ParameterizedTest
    @CsvSource({
            "2,     15000, order123, validPaymentKey",  // 잘못된 userId
            "1,     99999, order123, validPaymentKey",  // 잘못된 amount
            "1,     15000, wrongId,  validPaymentKey",  // 잘못된 orderId
            "1,     15000, order123, wrongKey",         // 잘못된 paymentKey
    })
    @DisplayName("validateConfirmRequest - 유효하지 않은 요청: PaymentValidException 발생")
    void validateConfirmRequest_invalid_throwsException(
            long userId, String amount, String orderId, String paymentKey) {
        PaymentEvent paymentEvent = defaultPaymentEvent();

        assertThatThrownBy(() ->
                paymentEvent.validateConfirmRequest(userId, new BigDecimal(amount), orderId, paymentKey))
                .isInstanceOf(PaymentValidException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING"})
    @DisplayName("격리 전환 시 READY/IN_PROGRESS/RETRYING 상태에서 QUARANTINED 상태로 변경되고 statusReason이 설정된다.")
    void quarantine_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus, PaymentOrderStatus.EXECUTING);
        String reason = "한도 소진 후 판단 불가";

        // when
        paymentEvent.quarantine(reason, LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
        assertThat(paymentEvent.getStatusReason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    @DisplayName("격리 전환 시 허용되지 않는 상태에서는 PaymentStatusException을 던진다.")
    void quarantine_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus, PaymentOrderStatus.EXECUTING);

        // when & then
        assertThatThrownBy(() -> paymentEvent.quarantine("reason", LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    // T1-04: 스펙 지정 테스트 메서드 (QUARANTINED non-terminal 설계 검증)

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "EXPIRED"})
    @DisplayName("execute() 호출 시 종결 상태(DONE/FAILED/CANCELED/EXPIRED)에서 PaymentStatusException을 던진다.")
    void execute_ThrowsException_WhenTerminalStatus(PaymentEventStatus terminalStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                terminalStatus, PaymentOrderStatus.EXECUTING);
        LocalDateTime executedAt = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

        // when & then
        assertThatThrownBy(() -> paymentEvent.execute("key", executedAt, LocalDateTime.now()))
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING", "QUARANTINED"})
    @DisplayName("quarantine() 호출 시 비종결 상태(QUARANTINED 포함)에서 QUARANTINED 전이 성공한다.")
    void quarantine_AlwaysSucceeds_FromAnyNonTerminal(PaymentEventStatus nonTerminalStatus) {
        // given — QUARANTINED가 non-terminal이어야 이 테스트가 GREEN
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                nonTerminalStatus, PaymentOrderStatus.EXECUTING);
        String reason = "복구 워커 보정 대기";

        // when
        paymentEvent.quarantine(reason, LocalDateTime.now());

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
        assertThat(paymentEvent.getStatusReason()).isEqualTo(reason);
    }

    @ParameterizedTest
    @EnumSource(PaymentGatewayType.class)
    @DisplayName("create() 호출 시 전달한 gatewayType이 getGatewayType()으로 반환된다.")
    void create_WithGatewayType_SetsGatewayType(PaymentGatewayType gatewayType) {
        // given
        UserInfo userInfo = UserInfo.builder().id(1L).build();
        ProductInfo productInfo = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(new BigDecimal("5000"))
                .stock(100)
                .sellerId(2L)
                .build();

        // when
        PaymentEvent paymentEvent = PaymentEvent.create(
                userInfo,
                List.of(productInfo),
                "order123",
                LocalDateTime.now(),
                gatewayType
        );

        // then
        assertThat(paymentEvent.getGatewayType()).isEqualTo(gatewayType);
    }
}
