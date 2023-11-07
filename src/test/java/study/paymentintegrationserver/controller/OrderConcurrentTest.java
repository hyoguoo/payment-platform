package study.paymentintegrationserver.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;
import study.paymentintegrationserver.repository.OrderInfoRepository;
import study.paymentintegrationserver.repository.ProductRepository;
import study.paymentintegrationserver.repository.UserRepository;
import study.paymentintegrationserver.service.PaymentService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static study.paymentintegrationserver.TestDataFactory.*;

@SpringBootTest
class OrderConcurrentTest {

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
    private List<OrderInfo> savedOrderList;

    @BeforeEach
    void setUp() {
        orderInfoRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() {
        orderInfoRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    @CsvSource({
            "300, 300, 300, 0, 0",
            "300, 299, 299, 0, 1",
            "300, 301, 300, 1, 0",
            "300, 350, 300, 50, 0",
    })
    @ParameterizedTest
    @DisplayName("동시에 승인 요청을 보내면 재고만큼 승인되고 나머지는 실패한다.")
    void approveOrderWithMultipleRequests(int stock, int orderCount, int expectedSuccess, int expectedFail, int expectedStock) {
        product = productRepository.save(generateProductWithPriceAndStock(BigDecimal.valueOf(1000), stock));
        user = userRepository.save(generateUser());
        savedOrderList = getSavedOrderList(orderCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        executeConcurrentActions(orderIndex -> {
            try {
                // Generate Request
                OrderInfo orderInfo = savedOrderList.get(orderIndex);
                String clientRequestPaymentKey = "test_payment_key";
                TossPaymentResponse tossPaymentResponse = generateDonePaymentResponse(clientRequestPaymentKey, orderInfo.getOrderId(), orderInfo.getOrderName(), orderInfo.getTotalAmount());
                OrderConfirmRequest orderConfirmRequest = generateOrderConfirmRequest(user.getId(), orderInfo.getOrderId(), orderInfo.getTotalAmount(), clientRequestPaymentKey);

                // Mocking Payment Service
                when(paymentService.getPaymentInfoByOrderId(any())).thenReturn(tossPaymentResponse);
                when(paymentService.confirmPayment(any())).thenReturn(tossPaymentResponse);

                // Execute
                orderController.confirmOrder(orderConfirmRequest);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            }
        }, orderCount, 32);

        Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();

        assertThat(updatedProduct.getStock()).isEqualTo(expectedStock);
        assertThat(successCount.get()).isEqualTo(expectedSuccess);
        assertThat(failCount.get()).isEqualTo(expectedFail);
    }

    private List<OrderInfo> getSavedOrderList(int orderCount) {
        List<OrderInfo> orderInfoList = new ArrayList<>();
        for (long i = 1; i <= orderCount; i++) {
            final Integer quantity = 1;
            OrderInfo orderInfo = generateOrderInfoWithTotalAmountAndQuantity(i, user, product, product.getPrice(), quantity);
            OrderInfo savedOrder = orderInfoRepository.save(orderInfo);
            orderInfoList.add(savedOrder);
        }
        return orderInfoList;
    }

    private void executeConcurrentActions(Consumer<Integer> action, int repeatCount, int threadSize) {
        AtomicInteger atomicInteger = new AtomicInteger();
        CountDownLatch countDownLatch = new CountDownLatch(repeatCount);
        ExecutorService executorService = Executors.newFixedThreadPool(threadSize);

        for (int i = 1; i <= repeatCount; i++) {
            executorService.execute(() -> {
                int index = atomicInteger.incrementAndGet() - 1;
                action.accept(index);
                countDownLatch.countDown();
            });
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
