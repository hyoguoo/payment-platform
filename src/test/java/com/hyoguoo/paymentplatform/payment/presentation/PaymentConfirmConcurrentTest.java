package com.hyoguoo.paymentplatform.payment.presentation;

import static com.hyoguoo.paymentplatform.mock.FakeTossSuccessHttpOperator.TEST_ORDER_ID;
import static com.hyoguoo.paymentplatform.mock.FakeTossSuccessHttpOperator.TEST_PAYMENT_KEY;
import static com.hyoguoo.paymentplatform.mock.FakeTossSuccessHttpOperator.TEST_TOTAL_AMOUNT_1;
import static com.hyoguoo.paymentplatform.mock.FakeTossSuccessHttpOperator.TEST_TOTAL_AMOUNT_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.IntegrationTest;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.mock.FakeTossSuccessHttpOperator;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.infrastructure.repository.JpaProductRepository;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Tag("TooLongIntegrationTest")
class PaymentConfirmConcurrentTest extends IntegrationTest {

    private static final int THREAD_COUNT = 32;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;
    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;
    @Autowired
    private JpaProductRepository jpaProductRepository;
    @Autowired
    private HttpOperator httpOperator;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaPaymentOrderRepository.deleteAllInBatch();
    }

    @ParameterizedTest
    @CsvSource({
            "1000, 1000, 1000, 0, 0",   // 재고와 주문 수량이 일치
            "1000, 999, 999, 0, 1",     // 주문 수량이 재고보다 적음
            "1000, 1001, 1000, 1, 0",   // 주문 수량이 재고보다 많음
            "1000, 1050, 1000, 50, 0",  // 재고 초과 주문 (50개 초과 주문 실패)
            "1200, 1000, 1000, 0, 200", // 재고가 1200개, 주문 수량은 1000개 (200개 남음)
    })
    @DisplayName("멀티스레드로 Payment Confirm 요청 시 결제 승인 처리와 상태가 동시성에 맞게 처리된다.")
    void concurrentConfirmPayment_withStock(
            int stock,
            int orderCount,
            int expectedSuccess,
            int expectedFail,
            int expectedStock
    ) {
        // given
        int minDelayMills = 30;
        int maxDelayMills = 50;

        initData(stock, orderCount);
        ReflectionTestUtils.invokeMethod(httpOperator, "setDelayRange", minDelayMills, maxDelayMills);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        executeConcurrentActions(orderIndex -> {
            try {
                PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                        .userId(1L)
                        .orderId(TEST_ORDER_ID + orderIndex)
                        .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                        .paymentKey(TEST_PAYMENT_KEY)
                        .build();

                MvcResult mvcResult = mockMvc.perform(
                        post("/api/v1/payments/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(confirmRequest))
                ).andReturn();
                if (mvcResult.getResponse().getStatus() == 200) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, orderCount);

        // then
        Product updatedProduct = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        assertThat(successCount.get()).isEqualTo(expectedSuccess);
        assertThat(failCount.get()).isEqualTo(expectedFail);
        assertThat(updatedProduct.getStock()).isEqualTo(expectedStock);
    }

    @ParameterizedTest
    @CsvSource({
            "500, 1000",
            "1000, 3000",
            "3000, 7000",
    })
    @DisplayName("멀티스레드로 Payment Confirm 요청 시 결제 승인 처리와 상태가 동시성에 맞게 처리된다.")
    void confirmPayment_withTimeout(
            int minDelayMills,
            int maxDelayMills
    ) {
        // given
        int stock = 100;
        int orderCount = 100;
        int expectedSuccess = 100;
        int expectedFail = 0;
        int expectedStock = 0;

        initData(stock, orderCount);
        ReflectionTestUtils.invokeMethod(httpOperator, "setDelayRange", minDelayMills, maxDelayMills);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        // when
        executeConcurrentActions(orderIndex -> {
            try {
                PaymentConfirmRequest confirmRequest = PaymentConfirmRequest.builder()
                        .userId(1L)
                        .orderId(TEST_ORDER_ID + orderIndex)
                        .amount(BigDecimal.valueOf(TEST_TOTAL_AMOUNT_1 + TEST_TOTAL_AMOUNT_2))
                        .paymentKey(TEST_PAYMENT_KEY)
                        .build();

                MvcResult mvcResult = mockMvc.perform(
                        post("/api/v1/payments/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(confirmRequest))
                ).andReturn();
                if (mvcResult.getResponse().getStatus() == 200) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, orderCount);

        // then
        Product updatedProduct = jpaProductRepository.findById(1L).orElseThrow().toDomain();
        assertThat(successCount.get()).isEqualTo(expectedSuccess);
        assertThat(failCount.get()).isEqualTo(expectedFail);
        assertThat(updatedProduct.getStock()).isEqualTo(expectedStock);
    }

    private void initData(int stock, int orderCount) {
        StringBuilder paymentEventInsertSql = new StringBuilder("""
                INSERT INTO payment_event
                    (id, buyer_id, seller_id, order_name, order_id, payment_key, status, approved_at, created_at, updated_at)
                VALUES
                """);

        StringBuilder paymentOrderInsertSql = new StringBuilder("""
                INSERT INTO payment_order
                    (id, payment_event_id, order_id, product_id, quantity, status, amount, created_at, updated_at)
                VALUES
                """);

        for (long i = 1; i <= orderCount; i++) {
            String orderId = TEST_ORDER_ID + i;

            paymentEventInsertSql.append(String.format(
                    "(%d, %d, %d, '%s', '%s', %s, '%s', %s, NOW(), NOW()), ",
                    i, 1L, 2L, "Ogu T 포함 2건", orderId, "NULL", "READY", "NULL"
            ));

            paymentOrderInsertSql.append(String.format(
                    "(%d, %d, '%s', %d, %d, '%s', %f, NOW(), NOW()), ",
                    i * 2 - 1, i, orderId, 1L, 1, "NOT_STARTED", TEST_TOTAL_AMOUNT_1
            ));

            paymentOrderInsertSql.append(String.format(
                    "(%d, %d, '%s', %d, %d, '%s', %f, NOW(), NOW()), ",
                    i * 2, i, orderId, 2L, 2, "NOT_STARTED", TEST_TOTAL_AMOUNT_2
            ));
        }

        // ", " 제거
        paymentEventInsertSql.setLength(paymentEventInsertSql.length() - 2);
        paymentOrderInsertSql.setLength(paymentOrderInsertSql.length() - 2);

        jdbcTemplate.update(paymentEventInsertSql.toString());
        jdbcTemplate.update(paymentOrderInsertSql.toString());

        String updateProductStockSql = """
                UPDATE product
                SET stock = ?
                WHERE id = ?
                """;

        jdbcTemplate.update(updateProductStockSql, stock, 1L);
        jdbcTemplate.update(updateProductStockSql, stock * 2, 2L);
    }

    private void executeConcurrentActions(
            Consumer<Integer> action,
            int repeatCount
    ) {
        AtomicInteger atomicInteger = new AtomicInteger();
        CountDownLatch countDownLatch = new CountDownLatch(repeatCount);
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 1; i <= repeatCount; i++) {
            executorService.execute(() -> {
                int index = atomicInteger.incrementAndGet();
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

    @TestConfiguration
    static class TestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new FakeTossSuccessHttpOperator();
        }
    }
}
