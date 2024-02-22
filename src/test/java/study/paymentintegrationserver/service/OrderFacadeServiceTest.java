package study.paymentintegrationserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static study.paymentintegrationserver.TestDataFactory.generateCancelPaymentResponse;
import static study.paymentintegrationserver.TestDataFactory.generateDonePaymentResponse;
import static study.paymentintegrationserver.TestDataFactory.generateInProgressPaymentResponse;
import static study.paymentintegrationserver.TestDataFactory.generateOrderCancelRequest;
import static study.paymentintegrationserver.TestDataFactory.generateOrderConfirmRequest;
import static study.paymentintegrationserver.TestDataFactory.generateOrderCreateRequest;
import static study.paymentintegrationserver.TestDataFactory.generateOrderInfoWithTotalAmountAndQuantity;
import static study.paymentintegrationserver.TestDataFactory.generateProductWithPriceAndStock;
import static study.paymentintegrationserver.TestDataFactory.generateUser;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;
import study.paymentintegrationserver.dto.order.OrderFindDetailResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;

class OrderFacadeServiceTest {

    private final static String DEFAULT_PAYMENT_KEY = "test_payment_key";
    private final static String DEFAULT_ORDER_NAME = "order_name";
    private final static String DEFAULT_ORDER_ID = "order_id";
    private final static BigDecimal DEFAULT_PRODUCT_PRICE = BigDecimal.valueOf(10000);
    private final static Integer DEFAULT_STOCK = 10;
    private final static Integer DEFAULT_QUANTITY = 1;
    private final static BigDecimal DEFAULT_TOTAL_AMOUNT = DEFAULT_PRODUCT_PRICE.multiply(
            BigDecimal.valueOf(DEFAULT_QUANTITY));
    private final static User DEFAULT_USER = generateUser();
    private final static Product DEFAULT_PRODUCT = generateProductWithPriceAndStock(
            DEFAULT_PRODUCT_PRICE, DEFAULT_STOCK);

    @Mock
    private OrderService orderService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private ProductService productService;

    @InjectMocks
    private OrderFacadeService orderFacadeService;

    @Mock
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("주어진 ID로 토스에 주문 정보를 조회하고, 조회된 주문 정보에 결제 정보를 추가하여 반환합니다.")
    void getOrderDetailsByIdAndUpdatePaymentInfo() {
        // Given
        final Long orderId = 1L;
        OrderInfo orderInfo = generateOrderInfoWithTotalAmountAndQuantity(
                1L,
                DEFAULT_USER,
                DEFAULT_PRODUCT,
                DEFAULT_TOTAL_AMOUNT,
                DEFAULT_QUANTITY
        );

        when(orderService.getOrderInfoById(orderId)).thenReturn(orderInfo);
        when(paymentService.findPaymentInfoByOrderId(orderInfo.getOrderId()))
                .thenReturn(Optional.of(
                                generateDonePaymentResponse(
                                        DEFAULT_PAYMENT_KEY,
                                        DEFAULT_ORDER_ID,
                                        DEFAULT_ORDER_NAME,
                                        BigDecimal.TEN
                                )
                        )
                );

        // When
        OrderFindDetailResponse result = orderFacadeService
                .getOrderDetailsByIdAndUpdatePaymentInfo(orderId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(orderInfo.getOrderId());
        assertThat(result.getPaymentKey()).isEqualTo(DEFAULT_PAYMENT_KEY);
    }

    @Test
    @DisplayName("주문 생성 시 주문 정보를 생성하고 ID를 반환합니다.")
    void testCreateOrder() {
        // Given
        OrderCreateRequest orderCreateRequest = generateOrderCreateRequest(
                DEFAULT_USER,
                DEFAULT_PRODUCT,
                DEFAULT_QUANTITY
        );

        when(userService.getById(any())).thenReturn(DEFAULT_USER);
        when(productService.getById(any())).thenReturn(DEFAULT_PRODUCT);
        when(orderService.saveOrderInfo(any()))
                .thenReturn(
                        generateOrderInfoWithTotalAmountAndQuantity(
                                1L,
                                DEFAULT_USER,
                                DEFAULT_PRODUCT,
                                DEFAULT_TOTAL_AMOUNT,
                                DEFAULT_QUANTITY
                        )
                );

        // When
        OrderCreateResponse result = orderFacadeService.createOrder(orderCreateRequest);

        // Then
        assertThat(result.getOrderId()).isNotNull();
    }

    @Test
    @DisplayName("주문 승인 요청 시 주문 정보를 조회하고, 결제 정보를 조회하여 주문 정보를 업데이트하고, 상품 재고를 차감 메서드를 호출합니다.")
    void testConfirmOrder() {
        // Given
        OrderInfo orderInfo = generateOrderInfoWithTotalAmountAndQuantity(
                1L,
                DEFAULT_USER,
                DEFAULT_PRODUCT,
                DEFAULT_TOTAL_AMOUNT,
                DEFAULT_QUANTITY
        );
        OrderConfirmRequest orderConfirmRequest = generateOrderConfirmRequest(
                DEFAULT_USER.getId(),
                orderInfo.getOrderId(),
                DEFAULT_TOTAL_AMOUNT,
                DEFAULT_PAYMENT_KEY
        );

        when(orderService.getOrderInfoByOrderId(orderConfirmRequest.getOrderId()))
                .thenReturn(orderInfo);
        when(orderService.getOrderInfoById(orderInfo.getId()))
                .thenReturn(orderInfo);
        when(productService.reduceStockWithCommit(any(), any()))
                .thenReturn(DEFAULT_PRODUCT);
        when(paymentService.getPaymentInfoByOrderId(any()))
                .thenReturn(
                        generateInProgressPaymentResponse(
                                DEFAULT_PAYMENT_KEY,
                                orderInfo.getOrderId(),
                                DEFAULT_ORDER_NAME,
                                DEFAULT_TOTAL_AMOUNT
                        )
                );
        when(paymentService.confirmPayment(any()))
                .thenReturn(
                        generateDonePaymentResponse(
                                DEFAULT_PAYMENT_KEY,
                                orderInfo.getOrderId(),
                                DEFAULT_ORDER_NAME,
                                DEFAULT_TOTAL_AMOUNT
                        )
                );
        when(orderService.saveOrderInfo(any()))
                .thenReturn(orderInfo);
        when(orderService.confirmOrderInfo(any(), any(), any()))
                .thenReturn(orderInfo);

        // When
        OrderConfirmResponse result = orderFacadeService.confirmOrder(orderConfirmRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(orderInfo.getOrderId());
        verify(paymentService, times(1))
                .confirmPayment(any());
        verify(productService, times(1))
                .reduceStockWithCommit(orderInfo.getProduct().getId(), orderInfo.getQuantity());
    }

    @Test
    @DisplayName("주문 취소 요청 시 주문 정보를 조회하고, 결제 정보를 조회하여 주문 정보를 업데이트하고, 상품 재고를 증가 메서드를 호출합니다.")
    void testCancelOrder() {
        // Given
        OrderInfo orderInfo = generateOrderInfoWithTotalAmountAndQuantity(
                1L,
                DEFAULT_USER,
                DEFAULT_PRODUCT,
                DEFAULT_TOTAL_AMOUNT,
                DEFAULT_QUANTITY
        );
        OrderCancelRequest orderCancelRequest = generateOrderCancelRequest(orderInfo.getOrderId());

        OrderConfirmRequest orderConfirmRequest = generateOrderConfirmRequest(
                DEFAULT_USER.getId(),
                orderInfo.getOrderId(),
                DEFAULT_TOTAL_AMOUNT,
                DEFAULT_PAYMENT_KEY
        );
        orderInfo.confirmOrder(
                generateDonePaymentResponse(
                        DEFAULT_PAYMENT_KEY,
                        orderInfo.getOrderId(),
                        DEFAULT_ORDER_NAME,
                        DEFAULT_TOTAL_AMOUNT
                ),
                orderConfirmRequest
        );
        when(orderService.getOrderInfoByOrderId(orderCancelRequest.getOrderId()))
                .thenReturn(orderInfo);
        when(paymentService.cancelPayment(any(), any()))
                .thenReturn(
                        generateCancelPaymentResponse(
                                DEFAULT_PAYMENT_KEY,
                                orderInfo.getOrderId(),
                                DEFAULT_ORDER_NAME,
                                DEFAULT_TOTAL_AMOUNT
                        )
                );
        when(orderService.saveOrderInfo(any())).thenReturn(orderInfo);

        // When
        OrderCancelResponse result = orderFacadeService.cancelOrder(orderCancelRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(orderInfo.getId());
        verify(paymentService, times(1))
                .cancelPayment(any(), any());
        verify(productService, times(1))
                .increaseStockWithCommit(orderInfo.getProduct().getId(), orderInfo.getQuantity());
    }
}
