package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> provideExecutedAtAndExpectedResult() {
        return Stream.of(
                Arguments.of(PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS - 1, false),
                Arguments.of(PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS + 1, true)
        );
    }

    static Stream<Arguments> provideRetryCountAndExpectedResult() {
        return Stream.of(
                Arguments.of(0, true),  // 재시도 횟수 0이면 재시도 가능
                Arguments.of(PaymentEvent.RETRYABLE_LIMIT - 1, true),  // 제한보다 하나 작은 경우 재시도 가능
                Arguments.of(PaymentEvent.RETRYABLE_LIMIT, false),  // 제한값에 도달한 경우 재시도 불가능
                Arguments.of(PaymentEvent.RETRYABLE_LIMIT + 1, false)  // 제한값을 넘은 경우 재시도 불가능
        );
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

    private PaymentEvent defaultRetryPaymentEventWithStatus(
            PaymentEventStatus paymentEventStatus,
            PaymentOrderStatus paymentOrderStatus,
            int retryCount,
            LocalDateTime executedAt
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
                .retryCount(retryCount)
                .executedAt(executedAt)
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
        PaymentEvent paymentEvent = PaymentEvent.requiredBuilder()
                .userInfo(userInfo)
                .productInfoList(productInfoList)
                .orderId(orderId)
                .requiredBuild();

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
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "UNKNOWN"})
    @DisplayName("결제 시작 시 특정 상태에서 성공적으로 IN_PROGRESS 상태로 변경하고, 실행 시간을 설정한다.")
    void execute_Success(PaymentEventStatus paymentEventStatus) {
        // given
        LocalDateTime executedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.NOT_STARTED
        );

        // when
        paymentEvent.execute("validPaymentKey", executedAt);

        // then
        assertThat(paymentEvent.getPaymentKey()).isEqualTo("validPaymentKey");
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
        assertThat(paymentEvent.getExecutedAt()).isEqualTo(executedAt);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED"})
    @DisplayName("결제 시작 시  in progress 상태로 변경 불가한 상태에서는 에외를 던진다.")
    void execute_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        LocalDateTime executedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.NOT_STARTED
        );

        // when & then
        assertThatThrownBy(() -> paymentEvent.execute("validPaymentKey", executedAt))
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"IN_PROGRESS", "DONE", "UNKNOWN"})
    @DisplayName("결제 완료 시 특정 상태에서 성공적으로 done 상태로 변경한다.")
    void done_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        LocalDateTime approvedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        // when
        paymentEvent.done(approvedAt);

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.DONE);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "FAILED", "CANCELED"})
    @DisplayName("결제 완료 시 done 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void done_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        LocalDateTime approvedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

        // when & then
        assertThatThrownBy(() -> paymentEvent.done(approvedAt))
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"IN_PROGRESS", "UNKNOWN"})
    @DisplayName("결제 실패 시 특정 상태에서 성공적으로 fail 상태로 변경한다.")
    void fail_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when
        paymentEvent.fail();

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "CANCELED"})
    @DisplayName("결제 실패 시 fail 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void fail_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when & then
        assertThatThrownBy(paymentEvent::fail)
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "UNKNOWN"})
    @DisplayName("알 수 없는 결과 처리 시 특정 상태에서 성공적으로 unknown 상태로 변경한다.")
    void unknown_Success(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.NOT_STARTED
        );

        // when
        paymentEvent.unknown();

        // then
        assertThat(paymentEvent.getStatus()).isEqualTo(PaymentEventStatus.UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED"})
    @DisplayName("알 수 없는 결과 처리 시 unknown 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void unknown_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when & then
        assertThatThrownBy(paymentEvent::unknown)
                .isInstanceOf(PaymentStatusException.class);
    }

    @Test
    @DisplayName("올바른 정보 입력 시 성공적으로 검증한다.")
    void validate_Success() {
        // given
        PaymentEvent paymentEvent = defaultPaymentEvent();

        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .userId(1L)
                .orderId("order123")
                .paymentKey("validPaymentKey")
                .amount(new BigDecimal(15000))
                .build();

        TossPaymentInfo paymentInfo = TossPaymentInfo.builder()
                .paymentKey("validPaymentKey")
                .orderId("order123")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(
                        TossPaymentDetails.builder()
                                .orderName("테스트 주문")
                                .totalAmount(new BigDecimal(15000))
                                .status(TossPaymentStatus.IN_PROGRESS)
                                .approvedAt(LocalDateTime.of(2021, 1, 1, 0, 0, 0))
                                .rawData("rawData")
                                .build())
                .build();

        // when & then
        paymentEvent.validateCompletionStatus(paymentConfirmCommand, paymentInfo);
    }

    @ParameterizedTest
    @CsvSource({
            "2, validPaymentKey, 15000, order123, INVALID_USER_ID",
            "1, invalidPaymentKey, 15000, order123, INVALID_PAYMENT_KEY",
            "1, validPaymentKey, 14000, order123, INVALID_TOTAL_AMOUNT",
            "1, validPaymentKey, 15000, wrongOrderId, INVALID_ORDER_ID"
    })
    @DisplayName("다양한 조건에서 검증 실패 시 PaymentValidException 예외가 발생한다.")
    void validate_InvalidCases(
            Long userId,
            String paymentKey,
            int amount,
            String orderId
    ) {
        // given
        PaymentEvent paymentEvent = defaultPaymentEvent();

        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .userId(userId)
                .orderId(orderId)
                .paymentKey(paymentKey)
                .amount(new BigDecimal(amount))
                .build();

        TossPaymentInfo paymentInfo = TossPaymentInfo.builder()
                .paymentKey("validPaymentKey")
                .orderId("order123")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(TossPaymentDetails.builder()
                        .orderName("테스트 주문")
                        .totalAmount(new BigDecimal(15000))
                        .status(TossPaymentStatus.IN_PROGRESS)
                        .approvedAt(LocalDateTime.of(2021, 1, 1, 0, 0, 0))
                        .rawData("rawData")
                        .build())
                .build();

        // when & then
        assertThatThrownBy(
                () -> paymentEvent.validateCompletionStatus(paymentConfirmCommand, paymentInfo))
                .isInstanceOf(PaymentValidException.class);
    }

    @ParameterizedTest
    @EnumSource(value = TossPaymentStatus.class, names = {
            "CANCELED", "EXPIRED", "PARTIAL_CANCELED", "ABORTED", "READY"
    })
    @DisplayName("승인 가능한 상태가 아닌 경우 예외를 던진다.")
    void validate_InvalidPaymentStatus(TossPaymentStatus tossPaymentStatus) {
        // given
        PaymentEvent paymentEvent = defaultPaymentEvent();

        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .userId(1L)
                .orderId("order123")
                .paymentKey("validPaymentKey")
                .amount(new BigDecimal(15000))
                .build();

        TossPaymentInfo paymentInfo = TossPaymentInfo.builder()
                .paymentKey("validPaymentKey")
                .orderId("order123")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(
                        TossPaymentDetails.builder()
                                .orderName("테스트 주문")
                                .totalAmount(new BigDecimal(15000))
                                .status(tossPaymentStatus)
                                .approvedAt(LocalDateTime.of(2021, 1, 1, 0, 0, 0))
                                .rawData("rawData")
                                .build())
                .build();

        // when & then
        assertThatThrownBy(
                () -> paymentEvent.validateCompletionStatus(paymentConfirmCommand, paymentInfo))
                .isInstanceOf(PaymentStatusException.class);
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

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"UNKNOWN", "IN_PROGRESS"})
    @DisplayName("Unknown 혹은 In Progress 상태의 이벤트의 재시도 횟수를 증가시킬 수 있다.")
    void increaseRetryCount(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when
        paymentEvent.increaseRetryCount();

        // then
        assertThat(paymentEvent.getRetryCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "DONE", "FAILED", "CANCELED"})
    @DisplayName("Unknown 상태가 아닌 이벤트의 재시도 횟수를 증가시킬 수 없다.")
    void increaseRetryCount_InvalidStatus(PaymentEventStatus paymentEventStatus) {
        // given
        PaymentEvent paymentEvent = defaultExecutedPaymentEventWithStatus(
                paymentEventStatus,
                PaymentOrderStatus.EXECUTING
        );

        // when & then
        assertThatThrownBy(paymentEvent::increaseRetryCount)
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @MethodSource("provideExecutedAtAndExpectedResult")
    @DisplayName("IN_PROGRESS 상태에서 실행 시간이 주어진 조건에 맞는 경우 재시도가 가능하다.")
    void isRetryableInProgress(long executedAtOffsetMinutes, boolean expectedResult) {
        // given
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime executedAt = now.minusMinutes(executedAtOffsetMinutes);

        PaymentEvent paymentEvent = defaultRetryPaymentEventWithStatus(
                PaymentEventStatus.IN_PROGRESS,
                PaymentOrderStatus.EXECUTING,
                0,
                executedAt
        );

        // when
        boolean result = paymentEvent.isRetryable(now);

        // then
        assertThat(result).isEqualTo(expectedResult); // 기대 결과와 비교
    }

    @ParameterizedTest
    @MethodSource("provideRetryCountAndExpectedResult")
    @DisplayName("IN_PROGRESS 상태에서 재시도 횟수에 따라 재시도가 가능하다.")
    void isRetryableInProgress_RetryCount(int retryCount, boolean expectedResult) {
        // given
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime executedAt = now.minusMinutes(PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS + 1);

        PaymentEvent paymentEvent = defaultRetryPaymentEventWithStatus(
                PaymentEventStatus.IN_PROGRESS,
                PaymentOrderStatus.EXECUTING,
                retryCount, // 파라미터로 받은 재시도 횟수 설정
                executedAt
        );

        // when
        boolean result = paymentEvent.isRetryable(now);

        // then
        assertThat(result).isEqualTo(expectedResult); // 기대 결과와 비교
    }

    @ParameterizedTest
    @MethodSource("provideRetryCountAndExpectedResult")
    @DisplayName("UNKNOWN 상태에서 재시도 횟수에 따라 재시도가 가능하다.")
    void isRetryableUnknown_RetryCount(int retryCount, boolean expectedResult) {
        // given
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime executedAt = now.minusMinutes(PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS + 1);

        PaymentEvent paymentEvent = defaultRetryPaymentEventWithStatus(
                PaymentEventStatus.UNKNOWN,
                PaymentOrderStatus.UNKNOWN,
                retryCount,
                executedAt
        );

        // when
        boolean result = paymentEvent.isRetryable(now);

        // then
        assertThat(result).isEqualTo(expectedResult);
    }
}
