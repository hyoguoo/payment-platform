package com.hyoguoo.paymentplatform.payment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("Checkout 멱등성 동시성 통합 테스트")
class PaymentCheckoutConcurrencyIntegrationTest extends BaseIntegrationTest {

    private static final int THREAD_COUNT = 5;
    private static final long TEST_USER_ID = 1L;
    private static final long TEST_PRODUCT_ID = 1L;
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @BeforeEach
    void setUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaPaymentOrderRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("동일 Idempotency-Key로 동시에 N개 요청이 들어와도 payment_event는 1개만 생성된다.")
    void checkout_동일_키_동시_요청_결제이벤트_1개만_생성() throws InterruptedException {
        // given
        CheckoutRequest checkoutRequest = buildCheckoutRequest();
        String body = writeJson(checkoutRequest);

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // when
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    mockMvc.perform(post("/api/v1/payments/checkout")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header(IDEMPOTENCY_KEY_HEADER, "concurrent-key-1")
                                    .content(body))
                            .andExpect(status().is2xxSuccessful());
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // then
        assertThat(successCount.get()).isEqualTo(THREAD_COUNT);
        assertThat(jpaPaymentEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 Idempotency-Key로 순차 요청 시 첫 번째는 201, 두 번째는 200을 반환한다.")
    void checkout_동일_키_순차_요청_첫번째_201_두번째_200() throws Exception {
        // given
        CheckoutRequest checkoutRequest = buildCheckoutRequest();
        String body = writeJson(checkoutRequest);

        // when & then
        mockMvc.perform(post("/api/v1/payments/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENCY_KEY_HEADER, "sequential-key-1")
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/payments/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IDEMPOTENCY_KEY_HEADER, "sequential-key-1")
                        .content(body))
                .andExpect(status().isOk());

        assertThat(jpaPaymentEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 Idempotency-Key로 동시에 N개 요청이 들어오면 payment_event가 N개 생성된다.")
    void checkout_다른_키_동시_요청_각각_독립_생성() throws InterruptedException {
        // given
        CheckoutRequest checkoutRequest = buildCheckoutRequest();
        String body = writeJson(checkoutRequest);

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            keys.add("unique-key-" + i);
        }
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // when
        for (int i = 0; i < THREAD_COUNT; i++) {
            String key = keys.get(i);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    mockMvc.perform(post("/api/v1/payments/checkout")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .header(IDEMPOTENCY_KEY_HEADER, key)
                                    .content(body))
                            .andExpect(status().isCreated());
                } catch (Exception ignored) {
                }
            });
        }

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // then
        assertThat(jpaPaymentEventRepository.count()).isEqualTo(THREAD_COUNT);
    }

    private CheckoutRequest buildCheckoutRequest() {
        return CheckoutRequest.builder()
                .userId(TEST_USER_ID)
                .orderedProductList(List.of(
                        OrderedProduct.builder()
                                .productId(TEST_PRODUCT_ID)
                                .quantity(1)
                                .build()
                ))
                .build();
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
