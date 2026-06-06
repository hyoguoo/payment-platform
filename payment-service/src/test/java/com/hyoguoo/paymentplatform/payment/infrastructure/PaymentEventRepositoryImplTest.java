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
        // created_at 은 Instant (BaseEntity, DATETIME(6)) 이나, raw SQL INSERT 에는 LocalDateTime 바인딩도
        // DATETIME(6) 컬럼에 그대로 저장된다. cutoff(Instant) 비교는 동일 UTC 기준으로 일관된다.
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
        // raw SQL INSERT 를 사용하지 않으므로 AuditingEntityListener(clockDateTimeProvider) 가 created_at 을 채운다.
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

        // when — cutoff 에 여유(+2초)를 준다.
        // payment_event.created_at 은 DATETIME(6) (마이크로초 정밀도, V4 승급)이라 저장 시 서브초 절삭이
        // 사실상 없으나, save~afterSave 사이 미세 시차를 흡수하기 위해 +2초 여유를 유지한다.
        // ※ dateTimeProviderRef 누락(기본 CurrentDateTimeProvider 회귀)을 JVM TZ 무관하게 잡는
        //   결정적 가드는 JpaAuditingProviderWiringTest 가 담당한다. 본 테스트는 통합 경로 스모크다.
        Instant cutoff = afterSave.plusSeconds(2);
        List<PaymentEvent> result = paymentEventRepository.findReadyPaymentsOlderThan(cutoff);

        // then — auditing 으로 채워진 created_at 이 cutoff 이전이라 조회된다
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds)
                .as("DM1 회귀: clockDateTimeProvider 경유 auditing created_at 이 cutoff 이전으로 조회 성립")
                .contains(entity.getId());

        // 추가 단정 — created_at 이 save 시점(beforeSave ~ afterSave) 근방으로 채워졌는지 확인.
        // 반올림 ±1초 + 여유로 ±2초 범위. 비-UTC JVM 에서 기본 provider 로 회귀하면 9시간 어긋나 실패.
        PaymentEvent loaded = paymentEventRepository.findById(entity.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt())
                .as("DM1: JPA save 후 created_at 이 Clock(UTC) 기준 save 시점 근방으로 채워져야 한다")
                .isAfterOrEqualTo(beforeSave.minusSeconds(2))
                .isBeforeOrEqualTo(afterSave.plusSeconds(2));
    }

    /**
     * P17 — D4(BaseEntity Instant 전환 + DATETIME(6) 승급) 후 created_at 의 Instant round-trip 완전 검증.
     *
     * <p>JPA save → flush → findById 경로에서 created_at 이 {@link Instant} 타입으로 저장·조회되고,
     * save 시점 근방으로 채워짐을 단정한다. DATETIME(6) 마이크로초 정밀도로 서브초 절삭이 없다.
     */
    @Test
    @DisplayName("save round-trip — created_at 이 Instant 로 저장·조회되고 save 시점 근방이다 (DATETIME(6))")
    void save_createdAt_Instant_roundTrip() {
        // given
        Instant beforeSave = Instant.now();

        PaymentEventEntity entity = PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("roundtrip-order")
                .orderId("roundtrip-order-id-1")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .build();
        jpaPaymentEventRepository.save(entity);
        jpaPaymentEventRepository.flush();

        Instant afterSave = Instant.now();

        // when
        PaymentEvent loaded = paymentEventRepository.findById(entity.getId()).orElseThrow();

        // then — Instant 타입 + save 시점 근방(±2초)
        assertThat(loaded.getCreatedAt())
                .as("P17: created_at 이 Instant 타입으로 round-trip 된다")
                .isInstanceOf(Instant.class)
                .isAfterOrEqualTo(beforeSave.minusSeconds(2))
                .isBeforeOrEqualTo(afterSave.plusSeconds(2));
    }
}
