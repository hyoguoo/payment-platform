package study.paymentintegrationserver.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static study.paymentintegrationserver.TestDataFactory.*;


class OrderInfoTest {

    private final static String DEFAULT_PAYMENT_KEY = "test_payment_key";
    private final static String DEFAULT_ORDER_NAME = "order_name";
    private final static BigDecimal DEFAULT_PRODUCT_PRICE = BigDecimal.valueOf(10000);
    private final static Integer DEFAULT_QUANTITY = 1;
    private final static BigDecimal DEFAULT_TOTAL_AMOUNT = DEFAULT_PRODUCT_PRICE.multiply(BigDecimal.valueOf(DEFAULT_QUANTITY));

    @Mock
    private User user;
    @Mock
    private Product product;
    @InjectMocks
    private OrderInfo orderInfo;

    static Stream<Arguments> validTotalAmountProvider() {
        return Stream.of(
                Arguments.of(BigDecimal.valueOf(10000), 1, BigDecimal.valueOf(10000)),
                Arguments.of(BigDecimal.valueOf(10000), 2, BigDecimal.valueOf(20000)),
                Arguments.of(BigDecimal.valueOf(10000), 3, BigDecimal.valueOf(30000))
        );
    }

    static Stream<Arguments> invalidTotalAmountProvider() {
        return Stream.of(
                Arguments.of(BigDecimal.valueOf(10000), 1, BigDecimal.valueOf(20000)),
                Arguments.of(BigDecimal.valueOf(10000), 2, BigDecimal.valueOf(30000)),
                Arguments.of(BigDecimal.valueOf(10000), 3, BigDecimal.valueOf(50000))
        );
    }

    @BeforeEach
    void setUp() {
        user = mock(User.class);
        product = mock(Product.class);
        when(user.getId()).thenReturn(1L);
        when(product.getId()).thenReturn(1L);
    }

    @MethodSource("validTotalAmountProvider")
    @ParameterizedTest
    @DisplayName("주문 클래스 생성 시 주문 정보를 생성합니다.")
    void givenValidOrderCreateRequest_whenCreateOrderInfo_thenOrderInfoIsCreated(BigDecimal productPrice, Integer quantity, BigDecimal totalAmount) {
        // Given
        when(this.product.getPrice()).thenReturn(productPrice);

        // When
        OrderInfo orderInfo = OrderInfo.builder()
                .user(user)
                .product(product)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .build();

        // Then
        assertThat(orderInfo.getUser()).isEqualTo(user);
        assertThat(orderInfo.getProduct()).isEqualTo(product);
        assertThat(orderInfo.getQuantity()).isEqualTo(quantity);
        assertThat(orderInfo.getTotalAmount()).isEqualTo(totalAmount);
    }

    @MethodSource("invalidTotalAmountProvider")
    @ParameterizedTest
    @DisplayName("주문 클래스 생성 시 주문 금액이 다를 경우 예외를 발생시킵니다.")
    void givenInvalidOrderCreateRequest_whenCreateOrderInfo_thenOrderInfoIsCreated(BigDecimal productPrice, Integer quantity, BigDecimal totalAmount) {
        // Given
        when(this.product.getPrice()).thenReturn(productPrice);

        // When, Then
        assertThatThrownBy(() -> OrderInfo.builder()
                .user(user)
                .product(product)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .build())
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.INVALID_TOTAL_AMOUNT.getMessage());
    }

    @MethodSource("validTotalAmountProvider")
    @ParameterizedTest
    @DisplayName("주문이 승인 요청 시 주문 정보를 갱신되며 DONE 상태로 변경합니다.")
    void confirmOrderWithSuccessPayment(BigDecimal productPrice, Integer quantity, BigDecimal totalAmount) {
        // Given
        when(this.product.getPrice()).thenReturn(productPrice);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, totalAmount, quantity);
        final String orderId = orderInfo.getOrderId();
        final Long userId = user.getId();

        TossPaymentResponse paymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, totalAmount);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(userId, orderId, totalAmount, DEFAULT_PAYMENT_KEY);

        // When
        OrderInfo updatedOrderInfo = orderInfo.confirmOrder(paymentInfo, orderConfirmRequest);

        // Then
        assertThat(updatedOrderInfo).isEqualTo(orderInfo);
        assertThat(updatedOrderInfo.getStatus()).isEqualTo(OrderInfo.OrderStatus.DONE.getStatusName());
    }

    @MethodSource("invalidTotalAmountProvider")
    @ParameterizedTest
    @DisplayName("주문 승인 요청 시 주문 금액이 다를 경우 예외를 발생시킵니다.")
    void confirmOrderWithInvalidTotalAmount(BigDecimal productPrice, Integer quantity, BigDecimal totalAmount) {
        // Given
        when(this.product.getPrice()).thenReturn(productPrice);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, productPrice.multiply(BigDecimal.valueOf(quantity)), quantity);
        final String orderId = orderInfo.getOrderId();
        final Long userId = user.getId();

        TossPaymentResponse paymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, totalAmount);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(userId, orderId, totalAmount, DEFAULT_PAYMENT_KEY);

        // When, Then
        assertThatThrownBy(() -> orderInfo.confirmOrder(paymentInfo, orderConfirmRequest))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.INVALID_TOTAL_AMOUNT.getMessage());
    }

    @Test
    @DisplayName("주문이 승인 요청 시 PaymentResponse가 DONE 아닌 경우 예외를 발생시킵니다.")
    void confirmOrderWithFailPayment() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();
        final Long userId = user.getId();

        TossPaymentResponse paymentInfo = generateCancelPaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(userId, orderId, DEFAULT_TOTAL_AMOUNT, DEFAULT_PAYMENT_KEY);

        // When, Then
        assertThatThrownBy(() -> orderInfo.confirmOrder(paymentInfo, orderConfirmRequest))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.NOT_DONE_PAYMENT.getMessage());
    }


    @Test
    @DisplayName("주문 승인 요청 시 paymentKey가 다를 경우 예외를 발생시킵니다.")
    void confirmOrderWithInvalidPaymentKey() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();
        final Long userId = user.getId();
        final String tossPaymentKey = "payment_key";
        final String orderPaymentKey = "not_equals_payment_key";

        TossPaymentResponse paymentInfo = generateDonePaymentResponse(tossPaymentKey, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(userId, orderId, DEFAULT_TOTAL_AMOUNT, orderPaymentKey);

        // When, Then
        assertThatThrownBy(() -> orderInfo.confirmOrder(paymentInfo, orderConfirmRequest))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.INVALID_PAYMENT_KEY.getMessage());
    }

    @Test
    @DisplayName("주문 승인 요청 시 user ID가 다를 경우 예외를 발생시킵니다.")
    void confirmOrderWithInvalidUserId() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();
        final Long userId = user.getId();
        final Long invalidUserId = userId + 1;

        TossPaymentResponse paymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(invalidUserId, orderId, DEFAULT_TOTAL_AMOUNT, DEFAULT_PAYMENT_KEY);

        // When, Then
        assertThatThrownBy(() -> orderInfo.confirmOrder(paymentInfo, orderConfirmRequest))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.INVALID_USER_ID.getMessage());
    }

    @Test
    @DisplayName("주문 승인 요청 시 order ID가 다를 경우 예외를 발생시킵니다.")
    void confirmOrderWithInvalidOrderId() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();
        final String invalidOrderId = orderId + "invalid";
        final Long userId = user.getId();

        TossPaymentResponse paymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(userId, invalidOrderId, DEFAULT_TOTAL_AMOUNT, DEFAULT_PAYMENT_KEY);

        // When, Then
        assertThatThrownBy(() -> orderInfo.confirmOrder(paymentInfo, orderConfirmRequest))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.INVALID_ORDER_ID.getMessage());
    }

    @Test
    @DisplayName("주문이 취소 요청 시 주문 정보를 갱신되며 CANCELED 상태로 변경합니다.")
    void cancelOrderWithSuccessPayment() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();

        TossPaymentResponse donePaymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(user.getId(), orderId, DEFAULT_TOTAL_AMOUNT, DEFAULT_PAYMENT_KEY);
        orderInfo.confirmOrder(donePaymentInfo, orderConfirmRequest);

        TossPaymentResponse cancelPaymentInfo = generateCancelPaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);

        // When
        OrderInfo updatedOrderInfo = orderInfo.cancelOrder(cancelPaymentInfo);

        // Then
        assertThat(updatedOrderInfo).isEqualTo(orderInfo);
        assertThat(updatedOrderInfo.getStatus()).isEqualTo(OrderInfo.OrderStatus.CANCELED.getStatusName());
    }

    @Test
    @DisplayName("주문 취소 요청 시 paymentKey가 다를 경우 예외를 발생시킵니다.")
    void cancelOrderWithInvalidPaymentKey() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();
        final String paymentKey1 = "payment_key";
        final String paymentKey2 = "not_equals_payment_key";

        TossPaymentResponse donePaymentInfo = generateDonePaymentResponse(paymentKey1, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(user.getId(), orderId, DEFAULT_TOTAL_AMOUNT, paymentKey1);
        orderInfo.confirmOrder(donePaymentInfo, orderConfirmRequest);

        TossPaymentResponse cancelPaymentInfo = generateCancelPaymentResponse(paymentKey2, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);

        // When, Then
        assertThatThrownBy(() -> orderInfo.cancelOrder(cancelPaymentInfo))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.INVALID_PAYMENT_KEY.getMessage());
    }

    @Test
    @DisplayName("주문 취소 요청 시 CANCLED 상태가 아닌 경우 예외를 발생시킵니다.")
    void cancelOrderWithFailPayment() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();

        TossPaymentResponse donePaymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(user.getId(), orderId, DEFAULT_TOTAL_AMOUNT, DEFAULT_PAYMENT_KEY);
        orderInfo.confirmOrder(donePaymentInfo, orderConfirmRequest);

        TossPaymentResponse cancelPaymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);

        // When, Then
        assertThatThrownBy(() -> orderInfo.cancelOrder(cancelPaymentInfo))
                .isInstanceOf(OrderInfoException.class)
                .hasMessageContaining(OrderInfoErrorMessage.NOT_CANCELED_PAYMENT.getMessage());
    }

    @Test
    @DisplayName("주문 정보 갱신 시 갱신 된 주문 정보를 갱신합니다.")
    void updatePaymentInfoWithSuccessPayment() {
        // Given
        when(this.product.getPrice()).thenReturn(DEFAULT_PRODUCT_PRICE);
        orderInfo = generateOrderInfoWithTotalAmountAndQuantity(user, this.product, DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY);
        final String orderId = orderInfo.getOrderId();

        TossPaymentResponse donePaymentInfo = generateDonePaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);
        OrderConfirmRequest orderConfirmRequest = generateRandomOrderConfirmRequest(user.getId(), orderId, DEFAULT_TOTAL_AMOUNT, DEFAULT_PAYMENT_KEY);
        orderInfo.confirmOrder(donePaymentInfo, orderConfirmRequest);

        TossPaymentResponse cancelPaymentInfo = generateCancelPaymentResponse(DEFAULT_PAYMENT_KEY, orderId, DEFAULT_ORDER_NAME, DEFAULT_TOTAL_AMOUNT);

        // When
        OrderInfo updatedOrderInfo = orderInfo.updatePaymentInfo(cancelPaymentInfo);

        // Then
        assertThat(updatedOrderInfo).isEqualTo(orderInfo);
        assertThat(updatedOrderInfo.getStatus()).isEqualTo(OrderInfo.OrderStatus.CANCELED.getStatusName());
    }
}
