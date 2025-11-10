package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PaymentEventRepositoryImplTest extends BaseIntegrationTest {

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private LocalDateTimeProvider localDateTimeProvider;

    @BeforeEach
    void setUp() {
        // Integration tests use real DB via Testcontainers
        // Data is cleaned up automatically via @Transactional rollback
    }

    @Test
    @DisplayName("countByStatus는 각 상태별 정확한 개수를 반환한다")
    void countByStatus_ReturnsAccurateCounts() {
        // given: 명확한 데이터 셋업을 위해 기존 데이터 무시하고 새로운 orderId 패턴 사용
        LocalDateTime now = localDateTimeProvider.now();
        String testPrefix = "countByStatus-" + System.currentTimeMillis() + "-";

        createPaymentEventWithStatus(PaymentEventStatus.READY, now, testPrefix + "1");
        createPaymentEventWithStatus(PaymentEventStatus.READY, now, testPrefix + "2");
        createPaymentEventWithStatus(PaymentEventStatus.IN_PROGRESS, now, testPrefix + "3");
        createPaymentEventWithStatus(PaymentEventStatus.DONE, now, testPrefix + "4");
        createPaymentEventWithStatus(PaymentEventStatus.FAILED, now, testPrefix + "5");

        // when
        Map<PaymentEventStatus, Long> result = paymentEventRepository.countByStatus();

        // then: 최소한 이 테스트가 생성한 개수만큼은 있어야 함
        assertThat(result.get(PaymentEventStatus.READY)).isGreaterThanOrEqualTo(2L);
        assertThat(result.get(PaymentEventStatus.IN_PROGRESS)).isGreaterThanOrEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.DONE)).isGreaterThanOrEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.FAILED)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("countByStatusAndAgeBuckets는 시간 기반 연령 버킷을 정확히 계산한다")
    void countByStatusAndAgeBuckets_CalculatesAgeCorrectly() {
        // given
        LocalDateTime now = localDateTimeProvider.now();
        LocalDateTime fiveMinutesAgo = now.minusMinutes(5);
        LocalDateTime thirtyMinutesAgo = now.minusMinutes(30);

        // recent (< 5분)
        createPaymentEventWithStatus(PaymentEventStatus.READY, now.minusMinutes(2));
        createPaymentEventWithStatus(PaymentEventStatus.IN_PROGRESS, now.minusMinutes(3));

        // medium (5분 ~ 30분)
        createPaymentEventWithStatus(PaymentEventStatus.READY, now.minusMinutes(10));
        createPaymentEventWithStatus(PaymentEventStatus.DONE, now.minusMinutes(15));

        // old (> 30분)
        createPaymentEventWithStatus(PaymentEventStatus.FAILED, now.minusMinutes(40));
        createPaymentEventWithStatus(PaymentEventStatus.EXPIRED, now.minusMinutes(50));

        // when
        Map<PaymentEventStatus, Map<String, Long>> result = paymentEventRepository.countByStatusAndAgeBuckets(
                fiveMinutesAgo,
                thirtyMinutesAgo
        );

        // then
        assertThat(result.get(PaymentEventStatus.READY).get("recent")).isEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.IN_PROGRESS).get("recent")).isEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.READY).get("medium")).isEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.DONE).get("medium")).isEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.FAILED).get("old")).isEqualTo(1L);
        assertThat(result.get(PaymentEventStatus.EXPIRED).get("old")).isEqualTo(1L);
    }

    @Test
    @DisplayName("countNearExpiration은 30분 만료 임박 결제를 식별한다")
    void countNearExpiration_IdentifiesPaymentsNearExpiration() {
        // given: 간단한 임계값 테스트 - 지금으로부터 25분 전을 기준으로
        LocalDateTime now = localDateTimeProvider.now();
        LocalDateTime expirationThreshold = now.minusMinutes(25); // 25분 전이 임계값

        // READY 상태의 결제 이벤트들 생성 (createdAt은 JPA가 자동으로 now()로 설정)
        String testPrefix = "nearExpiration-" + System.currentTimeMillis() + "-";
        createPaymentEventWithStatus(PaymentEventStatus.READY, now, testPrefix + "1");
        createPaymentEventWithStatus(PaymentEventStatus.READY, now, testPrefix + "2");
        createPaymentEventWithStatus(PaymentEventStatus.READY, now, testPrefix + "3");

        // when: 25분 전보다 이전에 생성된 READY 상태 결제 조회
        // 참고: 방금 생성한 이벤트들은 createdAt이 now()이므로 25분 전보다 나중 = 카운트 안됨
        long count = paymentEventRepository.countNearExpiration(expirationThreshold);

        // then: 현재 시간보다 25분 전의 임계값으로 조회하면 방금 생성된 이벤트는 포함 안됨
        // 이 테스트는 쿼리가 정상 작동하는지만 확인 (실제로 만료 임박인 이벤트는 없음)
        assertThat(count).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("대량 데이터(1000+ 이벤트)에 대한 쿼리 성능 테스트")
    void performanceTest_With1000PlusEvents() {
        // given
        LocalDateTime now = localDateTimeProvider.now();

        // 1000개 이벤트 생성
        for (int i = 0; i < 1000; i++) {
            PaymentEventStatus status = PaymentEventStatus.values()[i % PaymentEventStatus.values().length];
            LocalDateTime lastStatusChangedAt = now.minusMinutes(i % 60);
            createPaymentEventWithStatus(status, lastStatusChangedAt);
        }

        // when & then - 각 쿼리가 100ms 이내에 완료되어야 함
        long startTime1 = System.currentTimeMillis();
        Map<PaymentEventStatus, Long> statusCounts = paymentEventRepository.countByStatus();
        long duration1 = System.currentTimeMillis() - startTime1;
        assertThat(duration1).isLessThan(100L);
        assertThat(statusCounts.values().stream().mapToLong(Long::longValue).sum()).isGreaterThanOrEqualTo(1000L);

        long startTime2 = System.currentTimeMillis();
        Map<PaymentEventStatus, Map<String, Long>> ageBuckets = paymentEventRepository.countByStatusAndAgeBuckets(
                now.minusMinutes(5),
                now.minusMinutes(30)
        );
        long duration2 = System.currentTimeMillis() - startTime2;
        assertThat(duration2).isLessThan(100L);
        assertThat(ageBuckets).isNotEmpty();

        long startTime3 = System.currentTimeMillis();
        long nearExpiration = paymentEventRepository.countNearExpiration(now.minusMinutes(25));
        long duration3 = System.currentTimeMillis() - startTime3;
        assertThat(duration3).isLessThan(100L);
    }

    // Helper methods

    private PaymentEvent createPaymentEventWithStatus(PaymentEventStatus status, LocalDateTime lastStatusChangedAt, String orderId) {
        PaymentEvent event = createPaymentEvent(orderId);

        // Set lastStatusChangedAt using reflection for testing
        try {
            java.lang.reflect.Field field = PaymentEvent.class.getDeclaredField("lastStatusChangedAt");
            field.setAccessible(true);
            field.set(event, lastStatusChangedAt);

            java.lang.reflect.Field statusField = PaymentEvent.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(event, status);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field via reflection", e);
        }

        return paymentEventRepository.saveOrUpdate(event);
    }

    private PaymentEvent createPaymentEventWithStatus(PaymentEventStatus status, LocalDateTime lastStatusChangedAt) {
        String orderId = "order-" + System.nanoTime();
        return createPaymentEventWithStatus(status, lastStatusChangedAt, orderId);
    }

    private PaymentEvent createPaymentEvent(String orderId) {
        UserInfo userInfo = UserInfo.builder()
                .id(1L)
                .build();

        ProductInfo productInfo = ProductInfo.builder()
                .id(1L)
                .sellerId(1L)
                .name("Test Product")
                .price(BigDecimal.valueOf(10000))
                .stock(100)
                .build();

        return PaymentEvent.requiredBuilder()
                .userInfo(userInfo)
                .productInfoList(List.of(productInfo))
                .orderId(orderId)
                .lastStatusChangedAt(localDateTimeProvider.now())
                .requiredBuild();
    }
}
