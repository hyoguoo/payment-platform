package study.paymentintegrationserver.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import study.paymentintegrationserver.TestDataFactory;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;
import study.paymentintegrationserver.repository.OrderInfoRepository;
import study.paymentintegrationserver.repository.ProductRepository;
import study.paymentintegrationserver.repository.UserRepository;
import study.paymentintegrationserver.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static study.paymentintegrationserver.TestDataFactory.*;

@SpringBootTest
class OrderControllerTest {

    @Autowired
    private OrderController orderController;
    @Autowired
    private OrderInfoRepository orderInfoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProductRepository productRepository;
    @MockBean
    private PaymentService paymentService;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = userRepository.findById(1L).orElseThrow();
        product = productRepository.findById(1L).orElseThrow();
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("주문 생성 요청을 보내면 주문 생성 후 Order ID를 반환합니다.")
    void createOrder() {
        // Given
        final Integer quantity = 1;
        OrderCreateRequest orderCreateRequest = TestDataFactory.generateOrderCreateRequest(user, product, quantity);

        // When
        OrderCreateResponse createdOrderResponse = orderController.createOrder(orderCreateRequest);

        // Then
        OrderInfo createdOrder = orderInfoRepository.findByOrderId(createdOrderResponse.getOrderId()).orElseThrow();
        assertThat(createdOrderResponse.getOrderId()).isNotNull();
        assertThat(createdOrderResponse.getOrderId()).isEqualTo(createdOrder.getOrderId());
    }

    @Test
    @DisplayName("주문 승인 요청을 보내면 주문 상태를 승인으로 변경합니다.")
    void approveOrder() {
        // Given
        final Integer quantity = 1;
        String clientRequestPaymentKey = "test_payment_key";

        OrderInfo orderInfo = generateOrderInfoWithTotalAmountAndQuantity(1L, user, product, product.getPrice(), quantity);
        orderInfoRepository.save(orderInfo);
        OrderConfirmRequest orderConfirmRequest = generateOrderConfirmRequest(user.getId(), orderInfo.getOrderId(), orderInfo.getTotalAmount(), clientRequestPaymentKey);

        // When
        when(paymentService.getPaymentInfoByOrderId(any()))
                .thenReturn(generateDonePaymentResponse(clientRequestPaymentKey, orderInfo.getOrderId(), orderInfo.getOrderName(), orderInfo.getTotalAmount()));
        when(paymentService.confirmPayment(any()))
                .thenReturn(generateDonePaymentResponse(clientRequestPaymentKey, orderInfo.getOrderId(), orderInfo.getOrderName(), orderInfo.getTotalAmount()));
        OrderConfirmResponse confirmedOrderResponse = orderController.confirmOrder(orderConfirmRequest);

        // Then
        assertThat(confirmedOrderResponse.getOrderId()).isEqualTo(orderInfo.getOrderId());
        assertThat(confirmedOrderResponse.getAmount()).isEqualTo(orderInfo.getTotalAmount());
        OrderInfo confirmedOrder = orderInfoRepository.findByOrderId(confirmedOrderResponse.getOrderId()).orElseThrow();
        assertThat(confirmedOrder.getStatus()).isEqualTo("DONE");
        assertThat(confirmedOrder.getPaymentKey()).isEqualTo(clientRequestPaymentKey);
    }
}
