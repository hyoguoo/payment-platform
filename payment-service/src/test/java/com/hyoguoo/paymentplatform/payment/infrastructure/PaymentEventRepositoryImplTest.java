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
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
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

    private TimeZone originalTimeZone;

    @BeforeEach
    void cleanUp() {
        jpaPaymentEventRepository.deleteAllInBatch();
        originalTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
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
     * DM1 회귀 가드 — JPA auditing(@CreatedDate) 경로가 비-UTC JVM TZ 에서 UTC 기준으로 채워지는지 검증.
     *
     * <p>JVM TZ 를 Asia/Seoul(KST, UTC+9)로 강제한 상태에서 JPA save 를 수행하면,
     * {@code dateTimeProviderRef} 없이 Spring 기본 {@code CurrentDateTimeProvider} 를 사용하면
     * {@code LocalDateTime.now()} 가 KST wall-clock 값(UTC+9)으로 채워진다.
     * {@code Clock} 기반 {@code DateTimeProvider} 를 등록하면 UTC {@code LocalDateTime} 으로 채워진다.
     *
     * <p>cutoff(UTC Instant)와 created_at(LocalDateTime) 비교가 UTC 기준으로 일관돼야
     * 비-UTC JVM 에서 만료 조기/지연 오판이 없다(DM1 정합 보장).
     */
    @Test
    @DisplayName("DM1 회귀 — 비-UTC JVM TZ에서 JPA auditing created_at 이 UTC 기준으로 채워진다.")
    void auditing_nonUtcJvm_createdAtIsUtcBased() {
        // given — JVM TZ 를 KST 로 강제 (비-UTC 환경 재현)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));

        Instant beforeSave = Instant.now();

        // DM1 — JPA save 를 통해 실제 auditing(@CreatedDate) 경로를 밟는다.
        // raw SQL INSERT 를 사용하지 않으므로 AuditingEntityListener 가 created_at 을 채운다.
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
        // DateTimeProvider 가 UTC Clock 기반이면 created_at < cutoff(UTC) 로 정확히 비교된다.
        // KST wall-clock 으로 채워지면 created_at = 현재UTC+9h 가 저장되어
        // cutoff(UTC 현재) < created_at(KST 현재) 가 되어 조회 결과가 비어 오판된다.
        List<PaymentEvent> result = paymentEventRepository.findReadyPaymentsOlderThan(afterSave);

        // then — 방금 저장한 엔티티가 cutoff(now) 이전으로 조회되어야 한다
        // (Clock 기반 DateTimeProvider 가 UTC 기준으로 채우면 created_at < afterSave(UTC) 가 성립)
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds)
                .as("DM1: 비-UTC JVM에서 JPA auditing created_at 이 UTC 기준이면 cutoff 비교 정합")
                .contains(entity.getId());

        // 추가 단정 — DB 에서 created_at 을 직접 읽어 UTC 범위 안에 있는지 확인
        LocalDateTime storedCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM payment_event WHERE id = ?",
                LocalDateTime.class, entity.getId());
        assertThat(storedCreatedAt)
                .as("created_at 은 UTC 기준 LocalDateTime 이어야 한다 (KST = UTC+9 면 beforeSave+9h 범위로 어긋남)")
                .isAfterOrEqualTo(LocalDateTime.ofInstant(beforeSave.minusSeconds(1), ZoneOffset.UTC))
                .isBeforeOrEqualTo(LocalDateTime.ofInstant(afterSave.plusSeconds(1), ZoneOffset.UTC));
    }
}
