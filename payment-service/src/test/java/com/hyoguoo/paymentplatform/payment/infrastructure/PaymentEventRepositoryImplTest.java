package com.hyoguoo.paymentplatform.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentEventRepositoryImplTest extends BaseIntegrationTest {

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("findReadyPaymentsOlderThan(Instant) — cutoff 이전 READY 결제만 반환한다.")
    void findReadyPaymentsOlderThan_withInstantCutoff_returnsOnlyOlderPayments() {
        // given — READY 결제 2건을 raw SQL 로 직접 삽입하여 created_at 을 제어한다.
        // created_at 은 LocalDateTime (BaseEntity), JPQL 비교도 LocalDateTime 기준.
        // 테스트에서 두 건의 created_at 시각 차이를 명확히 하여 cutoff 경계를 검증한다.

        // older: 현재 UTC - 61분 (cutoff 이전 → 조회 대상)
        LocalDateTime olderCreatedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(61);
        // newer: 현재 UTC + 5분 (cutoff 이후 → 조회 제외)
        LocalDateTime newerCreatedAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5);

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, retry_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, "older-order", "older-order-id-1", "TOSS", "READY", 0,
                olderCreatedAt, olderCreatedAt);

        Long olderEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_event WHERE order_id = ?",
                Long.class, "older-order-id-1");

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, retry_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, "newer-order", "newer-order-id-1", "TOSS", "READY", 0,
                newerCreatedAt, newerCreatedAt);

        Long newerEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_event WHERE order_id = ?",
                Long.class, "newer-order-id-1");

        // cutoff = 현재 UTC - 30분 (older 포함, newer 제외)
        Instant cutoff = Instant.now().minusSeconds(30 * 60);

        // when
        List<PaymentEvent> result = paymentEventRepository.findReadyPaymentsOlderThan(cutoff);

        // then
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds).contains(olderEventId);
        assertThat(resultIds).doesNotContain(newerEventId);
    }

    /**
     * DM1 회귀 가드 — JPA save(@CreatedDate) 경로가 Clock 기반 DateTimeProvider 를 통해
     * Instant cutoff 와 정합하게 채워지는지 검증.
     *
     * <p>JPA save 를 통해 실제 auditing(@CreatedDate AuditingEntityListener) 경로를 밟고,
     * 저장 직후 {@code findReadyPaymentsOlderThan(cutoff)} 로 해당 엔티티가 조회됨을 단정한다.
     * {@code clockDateTimeProvider} 가 {@code Clock} 기반 UTC 시각을 공급하므로
     * cutoff(Instant) vs created_at(LocalDateTime) 비교가 동일 UTC 기준으로 일관된다.
     *
     * <p>회귀 가드 목적: Spring 기본 {@code CurrentDateTimeProvider} 로 돌아가거나
     * {@code dateTimeProviderRef} 설정이 빠지면 JVM TZ에 따라 auditing 시각 기준이
     * 어긋나 만료 cutoff 비교가 깨진다.
     */
    @Test
    @DisplayName("DM1 회귀 — JPA auditing created_at 이 Clock 기반 DateTimeProvider 를 통해 cutoff Instant 와 정합하게 채워진다.")
    void auditing_createdAt_isFilledByClockDateTimeProvider() {
        // given — JPA save 를 통해 실제 auditing(@CreatedDate) 경로를 밟는다.
        // raw SQL INSERT 를 사용하지 않으므로 AuditingEntityListener 가 created_at 을 채운다.
        Instant beforeSave = Instant.now();

        PaymentEventEntity entity = PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("auditing-test-order")
                .orderId("auditing-test-order-id-dm1")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .build();
        jpaPaymentEventRepository.save(entity);
        jpaPaymentEventRepository.flush();

        Instant afterSave = Instant.now();

        // when — findReadyPaymentsOlderThan 으로 cutoff(afterSave) 기준 조회
        // clockDateTimeProvider 가 Clock 기반 UTC LocalDateTime 을 공급하면
        // created_at(UTC LocalDateTime) < cutoff(UTC Instant) 비교가 일관되어 엔티티가 반환된다.
        // dateTimeProviderRef 가 없어 기본 CurrentDateTimeProvider 를 사용하면
        // JVM TZ 가 비-UTC 일 때 created_at 이 KST wall-clock 값으로 채워져 비교가 어긋난다.
        List<PaymentEvent> result = paymentEventRepository.findReadyPaymentsOlderThan(afterSave);

        // then — 방금 저장한 엔티티가 cutoff(now) 이전으로 조회되어야 한다
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds)
                .as("DM1 회귀: clockDateTimeProvider 가 Clock 기반 UTC 시각을 공급하면 created_at < afterSave(UTC) 성립")
                .contains(entity.getId());

        // 추가 단정 — beforeSave(UTC) ~ afterSave(UTC) 범위에 created_at 이 있어야 한다
        // JPA 도메인 객체를 다시 로드해 createdAt(Instant 기준) 로 범위 확인
        PaymentEvent loaded = paymentEventRepository.findById(entity.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt())
                .as("DM1: JPA save 후 created_at 이 Clock(UTC) 기준으로 채워져 beforeSave 이후이어야 한다")
                .isAfterOrEqualTo(beforeSave.minusSeconds(1))
                .isBeforeOrEqualTo(afterSave.plusSeconds(1));
    }
}
