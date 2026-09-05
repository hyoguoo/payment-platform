package com.hyoguoo.paymentplatform.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, "older-order", "older-order-id-1", "TOSS", "READY",
                olderCreatedAt, olderCreatedAt);

        Long olderEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM payment_event WHERE order_id = ?",
                Long.class, "older-order-id-1");

        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, "newer-order", "newer-order-id-1", "TOSS", "READY",
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
     * auditing UTC 일원화 회귀 가드 — JPA save(@CreatedDate) 경로가 Clock 기반 DateTimeProvider 를 통해
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
    @DisplayName("auditing UTC 일원화 회귀 — JPA auditing created_at 이 Clock 기반 DateTimeProvider 를 통해 cutoff Instant 와 정합하게 채워진다.")
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
     * BaseEntity Instant 전환 + DATETIME(6) 승급 후 created_at 의 Instant round-trip 완전 검증.
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

    // ────────────────────────────────────────────────────────────
    // resolveQuarantineToFailed — CAS 조건부 저장 (event + order 원자 동조)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveQuarantineToFailed — QUARANTINED 건은 1건 반영 + 자식 payment_order 전부 FAIL로 동조된다")
    void resolveQuarantineToFailed_whenQuarantined_updatesEventAndOrdersAtomically() {
        // given
        Long eventId = insertPaymentEvent("quarantine-cas-order-1", PaymentEventStatus.QUARANTINED, "격리 사유");
        insertPaymentOrder(eventId, "quarantine-cas-order-1", 1L, PaymentOrderStatus.EXECUTING);
        insertPaymentOrder(eventId, "quarantine-cas-order-1", 2L, PaymentOrderStatus.NOT_STARTED);

        Instant resolvedAt = Instant.now();

        // when
        boolean resolved = paymentEventRepository.resolveQuarantineToFailed(eventId, "관리자 안전 종결", resolvedAt);

        // then
        assertThat(resolved).isTrue();

        Map<String, Object> eventRow = jdbcTemplate.queryForMap(
                "SELECT status, status_reason FROM payment_event WHERE id = ?", eventId);
        assertThat(eventRow.get("status")).isEqualTo("FAILED");
        assertThat(eventRow.get("status_reason")).isEqualTo("관리자 안전 종결");

        List<String> orderStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM payment_order WHERE payment_event_id = ?", String.class, eventId);
        assertThat(orderStatuses).containsOnly("FAIL");
    }

    @Test
    @DisplayName("resolveQuarantineToFailed — 이미 FAILED로 종결된 건은 0건 충돌이며 event·order 모두 불변이다")
    void resolveQuarantineToFailed_whenAlreadyFailed_returnsFalseWithoutMutating() {
        // given — QUARANTINED가 아닌 상태(이미 종결된 FAILED)에 재시도
        Long eventId = insertPaymentEvent("quarantine-cas-order-2", PaymentEventStatus.FAILED, "기존 실패 사유");
        insertPaymentOrder(eventId, "quarantine-cas-order-2", 1L, PaymentOrderStatus.FAIL);

        // when
        boolean resolved = paymentEventRepository.resolveQuarantineToFailed(eventId, "새 종결 사유", Instant.now());

        // then
        assertThat(resolved).isFalse();

        Map<String, Object> eventRow = jdbcTemplate.queryForMap(
                "SELECT status, status_reason FROM payment_event WHERE id = ?", eventId);
        assertThat(eventRow.get("status")).isEqualTo("FAILED");
        assertThat(eventRow.get("status_reason")).isEqualTo("기존 실패 사유");

        List<String> orderStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM payment_order WHERE payment_event_id = ?", String.class, eventId);
        assertThat(orderStatuses).containsOnly("FAIL");
    }

    @Test
    @DisplayName("resolveQuarantineToFailed — 동시 2회 호출 시 1건만 성공한다 (CAS race 차단)")
    void resolveQuarantineToFailed_whenCalledConcurrently_onlyOneSucceeds() throws Exception {
        // given
        Long eventId = insertPaymentEvent("quarantine-cas-order-3", PaymentEventStatus.QUARANTINED, "격리 사유");
        insertPaymentOrder(eventId, "quarantine-cas-order-3", 1L, PaymentOrderStatus.EXECUTING);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Future<Boolean>> futures = new ArrayList<>();

        // when
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    boolean result = paymentEventRepository.resolveQuarantineToFailed(
                            eventId, "동시 종결", Instant.now());
                    doneLatch.countDown();
                    return result;
                }));
            }

            startLatch.countDown();
            doneLatch.await();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successCount++;
                }
            }

            // then — race 회귀 가드: 두 스레드 중 정확히 하나만 성공
            assertThat(successCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }

        List<String> orderStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM payment_order WHERE payment_event_id = ?", String.class, eventId);
        assertThat(orderStatuses).containsOnly("FAIL");
    }

    // ────────────────────────────────────────────────────────────
    // resolveInProgressToAwaitingResult — 1차 리컨실러 전이 CAS (payment_order 미터치)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveInProgressToAwaitingResult — IN_PROGRESS 건은 1건 갱신되어 AWAITING_RESULT 로 옮겨진다")
    void resolveInProgressToAwaitingResult_기대_상태와_같으면_1건을_갱신한다() {
        // given
        Long eventId = insertPaymentEvent("in-progress-to-awaiting-1", PaymentEventStatus.IN_PROGRESS, null);

        // when
        boolean resolved = paymentEventRepository.resolveInProgressToAwaitingResult(eventId, Instant.now());

        // then
        assertThat(resolved).isTrue();
        Map<String, Object> eventRow = jdbcTemplate.queryForMap(
                "SELECT status FROM payment_event WHERE id = ?", eventId);
        assertThat(eventRow.get("status")).isEqualTo("AWAITING_RESULT");
    }

    @Test
    @DisplayName("resolveInProgressToAwaitingResult — 이미 확정된(DONE) 건은 0건 충돌이며 상태가 그대로다")
    void resolveInProgressToAwaitingResult_기대_상태와_다르면_0건으로_끝난다() {
        // given — 조회 이후 다른 경로로 이미 확정(DONE)된 상황을 재현
        Long eventId = insertPaymentEvent("in-progress-to-awaiting-2", PaymentEventStatus.DONE, null);

        // when
        boolean resolved = paymentEventRepository.resolveInProgressToAwaitingResult(eventId, Instant.now());

        // then
        assertThat(resolved).isFalse();
        Map<String, Object> eventRow = jdbcTemplate.queryForMap(
                "SELECT status FROM payment_event WHERE id = ?", eventId);
        assertThat(eventRow.get("status")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("resolveInProgressToAwaitingResult — 동시 2회 호출 시 1건만 성공한다 (CAS race 차단)")
    void resolveInProgressToAwaitingResult_동시에_두_번_호출하면_하나만_성공한다() throws Exception {
        // given
        Long eventId = insertPaymentEvent("in-progress-to-awaiting-3", PaymentEventStatus.IN_PROGRESS, null);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Future<Boolean>> futures = new ArrayList<>();

        // when
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    boolean result = paymentEventRepository.resolveInProgressToAwaitingResult(
                            eventId, Instant.now());
                    doneLatch.countDown();
                    return result;
                }));
            }

            startLatch.countDown();
            doneLatch.await();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successCount++;
                }
            }

            // then
            assertThat(successCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("resolveInProgressToAwaitingResult — 성공해도 payment_order 행은 건드리지 않는다")
    void resolveInProgressToAwaitingResult_주문_행은_건드리지_않는다() {
        // given
        Long eventId = insertPaymentEvent("in-progress-to-awaiting-4", PaymentEventStatus.IN_PROGRESS, null);
        insertPaymentOrder(eventId, "in-progress-to-awaiting-4", 1L, PaymentOrderStatus.EXECUTING);

        // when
        boolean resolved = paymentEventRepository.resolveInProgressToAwaitingResult(eventId, Instant.now());

        // then
        assertThat(resolved).isTrue();
        List<String> orderStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM payment_order WHERE payment_event_id = ?", String.class, eventId);
        assertThat(orderStatuses).containsOnly("EXECUTING");
    }

    @Test
    @DisplayName("resolveInProgressToAwaitingResult — 성공하면 last_status_changed_at 이 전달한 시각으로 갱신된다")
    void resolveInProgressToAwaitingResult_성공하면_상태_변경_시각을_갱신한다() {
        // given
        Long eventId = insertPaymentEvent("in-progress-to-awaiting-5", PaymentEventStatus.IN_PROGRESS, null);
        Instant lastStatusChangedAt = Instant.now().plusSeconds(60);

        // when
        boolean resolved = paymentEventRepository.resolveInProgressToAwaitingResult(eventId, lastStatusChangedAt);

        // then — 2차 임계 조회가 재는 앵커이므로 SET 절 누락을 여기서 잡는다
        assertThat(resolved).isTrue();
        PaymentEvent saved = paymentEventRepository.findById(eventId).orElseThrow();
        assertThat(saved.getLastStatusChangedAt())
                .isCloseTo(lastStatusChangedAt, within(1, ChronoUnit.SECONDS));
    }

    // ────────────────────────────────────────────────────────────
    // resolveAwaitingResultToQuarantine — 2차 리컨실러 전이 CAS (payment_order 미터치, 되돌릴 길 없음)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveAwaitingResultToQuarantine — AWAITING_RESULT 건은 1건 갱신되어 QUARANTINED 로 옮겨진다")
    void resolveAwaitingResultToQuarantine_기대_상태와_같으면_1건을_갱신한다() {
        // given
        Long eventId = insertPaymentEvent("awaiting-to-quarantine-1", PaymentEventStatus.AWAITING_RESULT, null);

        // when
        boolean resolved = paymentEventRepository.resolveAwaitingResultToQuarantine(
                eventId, "2차 임계 초과", Instant.now());

        // then
        assertThat(resolved).isTrue();
        Map<String, Object> eventRow = jdbcTemplate.queryForMap(
                "SELECT status, status_reason FROM payment_event WHERE id = ?", eventId);
        assertThat(eventRow.get("status")).isEqualTo("QUARANTINED");
        assertThat(eventRow.get("status_reason")).isEqualTo("2차 임계 초과");
    }

    @Test
    @DisplayName("resolveAwaitingResultToQuarantine — 그 사이 확정된(DONE) 건은 0건 충돌이며 상태가 그대로다")
    void resolveAwaitingResultToQuarantine_기대_상태와_다르면_0건으로_끝난다() {
        // given — 조회 이후 확정 결과가 먼저 도착해 DONE 이 된 상황을 재현. 잘못 격리되면 되돌릴 길이 없다.
        Long eventId = insertPaymentEvent("awaiting-to-quarantine-2", PaymentEventStatus.DONE, null);

        // when
        boolean resolved = paymentEventRepository.resolveAwaitingResultToQuarantine(
                eventId, "2차 임계 초과", Instant.now());

        // then
        assertThat(resolved).isFalse();
        Map<String, Object> eventRow = jdbcTemplate.queryForMap(
                "SELECT status, status_reason FROM payment_event WHERE id = ?", eventId);
        assertThat(eventRow.get("status")).isEqualTo("DONE");
        assertThat(eventRow.get("status_reason")).isNull();
    }

    @Test
    @DisplayName("resolveAwaitingResultToQuarantine — 동시 2회 호출 시 1건만 성공한다 (CAS race 차단)")
    void resolveAwaitingResultToQuarantine_동시에_두_번_호출하면_하나만_성공한다() throws Exception {
        // given
        Long eventId = insertPaymentEvent("awaiting-to-quarantine-3", PaymentEventStatus.AWAITING_RESULT, null);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Future<Boolean>> futures = new ArrayList<>();

        // when
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    boolean result = paymentEventRepository.resolveAwaitingResultToQuarantine(
                            eventId, "동시 격리", Instant.now());
                    doneLatch.countDown();
                    return result;
                }));
            }

            startLatch.countDown();
            doneLatch.await();

            int successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successCount++;
                }
            }

            // then
            assertThat(successCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("resolveAwaitingResultToQuarantine — 성공해도 payment_order 행은 건드리지 않는다")
    void resolveAwaitingResultToQuarantine_주문_행은_건드리지_않는다() {
        // given
        Long eventId = insertPaymentEvent("awaiting-to-quarantine-4", PaymentEventStatus.AWAITING_RESULT, null);
        insertPaymentOrder(eventId, "awaiting-to-quarantine-4", 1L, PaymentOrderStatus.EXECUTING);

        // when
        boolean resolved = paymentEventRepository.resolveAwaitingResultToQuarantine(
                eventId, "2차 임계 초과", Instant.now());

        // then
        assertThat(resolved).isTrue();
        List<String> orderStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM payment_order WHERE payment_event_id = ?", String.class, eventId);
        assertThat(orderStatuses).containsOnly("EXECUTING");
    }

    @Test
    @DisplayName("resolveAwaitingResultToQuarantine — 성공하면 last_status_changed_at 이 전달한 시각으로 갱신된다")
    void resolveAwaitingResultToQuarantine_성공하면_상태_변경_시각을_갱신한다() {
        // given
        Long eventId = insertPaymentEvent("awaiting-to-quarantine-5", PaymentEventStatus.AWAITING_RESULT, null);
        Instant lastStatusChangedAt = Instant.now().plusSeconds(60);

        // when
        boolean resolved = paymentEventRepository.resolveAwaitingResultToQuarantine(
                eventId, "2차 임계 초과", lastStatusChangedAt);

        // then
        assertThat(resolved).isTrue();
        PaymentEvent saved = paymentEventRepository.findById(eventId).orElseThrow();
        assertThat(saved.getLastStatusChangedAt())
                .isCloseTo(lastStatusChangedAt, within(1, ChronoUnit.SECONDS));
    }

    // ────────────────────────────────────────────────────────────
    // findAwaitingResultOlderThan — 2차 임계 초과 조회 (앵커: last_status_changed_at)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAwaitingResultOlderThan — AWAITING_RESULT 이고 last_status_changed_at 이 기준시각보다 오래된 건만 반환한다")
    void findAwaitingResultOlderThan_결과_대기이고_기준시각보다_오래된_건만_반환한다() {
        // given
        Instant oldChangedAt = Instant.now().minusSeconds(600);
        Long olderEventId = insertPaymentEvent(
                "awaiting-older-1", PaymentEventStatus.AWAITING_RESULT, null, null, oldChangedAt);

        Instant recentChangedAt = Instant.now().plusSeconds(300);
        Long newerEventId = insertPaymentEvent(
                "awaiting-newer-1", PaymentEventStatus.AWAITING_RESULT, null, null, recentChangedAt);

        Instant cutoff = Instant.now();

        // when
        List<PaymentEvent> result = paymentEventRepository.findAwaitingResultOlderThan(cutoff);

        // then
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds).contains(olderEventId);
        assertThat(resultIds).doesNotContain(newerEventId);
    }

    @Test
    @DisplayName("findAwaitingResultOlderThan — AWAITING_RESULT 가 아닌 다른 상태는 반환하지 않는다")
    void findAwaitingResultOlderThan_다른_상태는_반환하지_않는다() {
        // given
        Instant oldChangedAt = Instant.now().minusSeconds(600);
        Long inProgressEventId = insertPaymentEvent(
                "awaiting-other-status-1", PaymentEventStatus.IN_PROGRESS, null, null, oldChangedAt);

        Instant cutoff = Instant.now();

        // when
        List<PaymentEvent> result = paymentEventRepository.findAwaitingResultOlderThan(cutoff);

        // then
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds).doesNotContain(inProgressEventId);
    }

    @Test
    @DisplayName("findAwaitingResultOlderThan — 확정 시작(executed_at)이 오래됐어도 결과 대기 진입(last_status_changed_at)이 최근이면 반환하지 않는다")
    void findAwaitingResultOlderThan_확정_시작은_오래됐어도_결과_대기_진입이_최근이면_반환하지_않는다() {
        // given — executed_at 은 2차 임계를 훌쩍 넘겼지만, last_status_changed_at(결과 대기 진입)은 방금이다.
        // 앵커를 executed_at 으로 잘못 잡으면 이 건이 조회되어 이 테스트가 실패한다.
        Instant longAgoExecutedAt = Instant.now().minusSeconds(3600);
        Instant justChangedAt = Instant.now().minusSeconds(1);
        Long eventId = insertPaymentEvent(
                "awaiting-anchor-guard-1", PaymentEventStatus.AWAITING_RESULT, null,
                longAgoExecutedAt, justChangedAt);

        Instant cutoff = Instant.now().minusSeconds(60);

        // when
        List<PaymentEvent> result = paymentEventRepository.findAwaitingResultOlderThan(cutoff);

        // then
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds).doesNotContain(eventId);
    }

    // ────────────────────────────────────────────────────────────
    // findByOrderIdForUpdate — 확정 결과 소비 경로용 잠금 읽기 (payment_outbox 의 findByOrderIdForUpdate 와 같은 형태)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByOrderIdForUpdate — 잠금 읽기가 기존 행을 반환한다")
    void findByOrderIdForUpdate_잠금_읽기가_기존_행을_반환한다() {
        // given
        Long eventId = insertPaymentEvent("lock-read-order-1", PaymentEventStatus.IN_PROGRESS, null);

        // when
        Optional<PaymentEvent> found = paymentEventRepository.findByOrderIdForUpdate("lock-read-order-1");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(eventId);
        assertThat(found.get().getOrderId()).isEqualTo("lock-read-order-1");
    }

    private Long insertPaymentEvent(String orderId, PaymentEventStatus status, String statusReason) {
        return insertPaymentEvent(orderId, status, statusReason, null, null);
    }

    private Long insertPaymentEvent(String orderId, PaymentEventStatus status, String statusReason,
            Instant executedAt, Instant lastStatusChangedAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO payment_event
                            (buyer_id, seller_id, order_name, order_id, gateway_type, status, status_reason,
                             executed_at, last_status_changed_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                1L, 2L, orderId + "-name", orderId, "TOSS", status.name(), statusReason,
                executedAt, lastStatusChangedAt, now, now);

        return jdbcTemplate.queryForObject(
                "SELECT id FROM payment_event WHERE order_id = ?", Long.class, orderId);
    }

    private void insertPaymentOrder(Long paymentEventId, String orderId, Long productId, PaymentOrderStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO payment_order
                            (payment_event_id, order_id, product_id, quantity, amount, status,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                paymentEventId, orderId, productId, 1, BigDecimal.valueOf(10000), status.name(), now, now);
    }
}
