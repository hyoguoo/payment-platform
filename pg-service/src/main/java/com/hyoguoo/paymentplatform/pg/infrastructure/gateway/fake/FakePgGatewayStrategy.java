package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics.CallOutcome;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fake PG 벤더 전략 — happy-path 통합 스모크 전용.
 *
 * <p><b>production 환경에서 절대 활성화 금지.</b>
 * 활성화 조건: {@code pg.gateway.type=fake} (smoke 프로파일에서만 override).
 * Toss(default), NicePay 와 상호 배타이므로 정상 환경에선 빈 등록되지 않는다.
 * 빈이 등록돼도 활성 프로파일에 {@code smoke} 나 {@code test} 가 없으면 기동 시점에
 * {@link IllegalStateException} 을 던져 프로세스를 종료한다 — 환경변수 실수로 일반 앱 스택에
 * fake 벤더가 주입되는 사고를 막는다.
 *
 * <p>해피 패스 전용 스트래티지:
 * <ul>
 *   <li>paymentKey 접두어 무관 → 첫 호출은 항상 APPROVED 반환</li>
 *   <li>approvedAt 은 {@link Clock} 기반 현재 시각</li>
 *   <li>브라우저 PG SDK 호출 없이 confirm 요청만으로 5-service chain smoke 가 완결되도록 설계</li>
 * </ul>
 *
 * <p>동일 paymentKey 로 재호출되면(재시도 자기루프) 실 벤더(Toss ALREADY_PROCESSED_PAYMENT)의 중복 승인
 * 응답을 모사한다 — {@link DuplicateApprovalDetectedEvent} 발행 후 {@link PgGatewayDuplicateHandledException}
 * throw. {@link com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.TossPaymentGatewayStrategy} 의
 * 이벤트+예외 이중 신호 순서를 그대로 따른다.
 *
 * <p>라이브 실측용으로 paymentKey 접두어에 따라 실패 경로를 고를 수 있다(임시 장치).
 * <ul>
 *   <li>{@code fake-fail-} — 확정 실패 → 보상 후 FAILED</li>
 *   <li>{@code fake-retry-} — 매 호출 재시도 가능 실패 → 시도 소진 → DLQ → QUARANTINED</li>
 *   <li>{@code fake-flaky-} — 두 번 실패한 뒤 세 번째 호출부터 승인 → 재시도 자가 회복</li>
 * </ul>
 * 접두어 판정은 중복 승인 판정보다 먼저 이뤄진다 — 자기루프 재호출마다 같은 실패를 내야
 * 시도 횟수가 실제로 소진되기 때문이다.
 *
 * @see com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.TossPaymentGatewayStrategy 프로덕션 Toss 구현
 * @see com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.NicepayPaymentGatewayStrategy 프로덕션 NicePay 구현
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pg.gateway.type", havingValue = "fake")
public class FakePgGatewayStrategy implements PgStatusLookupPort, PgConfirmPort {

    private static final String FAKE_PAYMENT_KEY_PREFIX = "fake-";
    private static final String ALREADY_PROCESSED_REASON_CODE = "ALREADY_PROCESSED_PAYMENT";

    /**
     * 라이브 실측용 시나리오 접두어 — paymentKey 앞머리로 벤더 응답을 결정적으로 고른다.
     * 실 벤더 없이 확정 실패 / 재시도 소진 / 자가 회복을 원하는 시점에 재현하기 위한 임시 장치다.
     */
    private static final String DRILL_FAIL_PREFIX = "fake-fail-";
    private static final String DRILL_RETRY_PREFIX = "fake-retry-";
    private static final String DRILL_FLAKY_PREFIX = "fake-flaky-";

    /**
     * 자가 회복 시나리오에서 성공 전까지 실패시킬 횟수 — 이 횟수를 넘긴 호출부터 승인된다.
     * <p>{@link com.hyoguoo.paymentplatform.pg.domain.RetryPolicy#MAX_ATTEMPTS} 보다 작아야 한다 —
     * 그렇지 않으면 회복 전에 재시도 한도가 소진돼 "자가 회복" 장면이 "격리" 장면으로 조용히 바뀐다.
     */
    private static final int DRILL_FLAKY_FAILURES_BEFORE_SUCCESS = 2;

    /**
     * fake 벤더 기동을 허용하는 활성 프로파일 목록 — 스모크/벤치마크 스택(smoke)과 pg 통합 테스트(test).
     * 일반 앱 스택(docker 단독)이나 프로파일 미지정 기동은 여기 없으므로 차단된다.
     */
    private static final List<String> BOOT_ALLOWED_PROFILES = List.of("smoke", "test");

    private final Clock clock;
    private final TossApiMetrics tossApiMetrics;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Environment environment;

    /**
     * 처음 confirm 에 성공한 orderId → 승인 결과 기록. key=orderId.
     * <p>동일 paymentKey 재호출(재시도 자기루프) 시 첫 호출 여부를 atomic 하게 판정하기 위해
     * {@code putIfAbsent} 를 사용한다 — self-loop 병렬 호출에서도 단 한 번만 happy-path 가 성립한다.
     */
    private final ConcurrentHashMap<String, PgConfirmResult> processedOrders = new ConcurrentHashMap<>();

    /**
     * 자가 회복 시나리오의 주문별 호출 횟수. key=orderId.
     * <p>같은 주문이 재시도 자기루프로 다시 들어올 때마다 증가하며, 정해진 실패 횟수를 넘기면 승인으로 넘어간다.
     */
    private final ConcurrentHashMap<String, AtomicInteger> drillFlakyAttempts = new ConcurrentHashMap<>();

    /**
     * 데모 부하 관측용 합성 벤더 RTT + 실패율 주입 파라미터.
     * <p>실 PG 호출이 없는 fake 모드에서 벤더 latency 패널을 실수치처럼 채우고("정상"),
     * fail-rate 로 비동기 실패 경로를 일부 태운다("이상 혼합"). 기본값은 happy-path 유지(fail-rate 0).
     * production 무관 — 이 빈 자체가 {@code pg.gateway.type=fake} 에서만 등록된다.
     */
    private final double failRate;
    private final long latencyMinMillis;
    private final long latencyMaxMillis;

    public FakePgGatewayStrategy(
            Clock clock,
            TossApiMetrics tossApiMetrics,
            ApplicationEventPublisher applicationEventPublisher,
            Environment environment,
            @Value("${pg.gateway.fake.fail-rate:0.0}") double failRate,
            @Value("${pg.gateway.fake.latency-min-millis:0}") long latencyMinMillis,
            @Value("${pg.gateway.fake.latency-max-millis:0}") long latencyMaxMillis
    ) {
        this.clock = clock;
        this.tossApiMetrics = tossApiMetrics;
        this.applicationEventPublisher = applicationEventPublisher;
        this.environment = environment;
        this.failRate = failRate;
        this.latencyMinMillis = latencyMinMillis;
        this.latencyMaxMillis = Math.max(latencyMaxMillis, latencyMinMillis);
    }

    @PostConstruct
    void warnActivation() {
        // 기동 배너 — LogFmt.banner 경유 필수 (CONVENTIONS: 기동 배너는 LogFmt.banner 로만 허용).
        // 시각적 경고로 prod 오염 사고를 방지한다.
        LogFmt.banner(log, Level.WARN,
                "╔══════════════════════════════════════════════════════════════╗",
                "║  FAKE PG STRATEGY ACTIVE — SMOKE PROFILE ONLY                ║",
                "║  PRODUCTION ENVIRONMENT MUST NOT ENABLE pg.gateway.type=fake ║",
                "╚══════════════════════════════════════════════════════════════╝"
        );

        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean allowed = activeProfiles.stream().anyMatch(BOOT_ALLOWED_PROFILES::contains);
        if (!allowed) {
            throw new IllegalStateException(
                    "FakePgGatewayStrategy 기동 차단 — 활성 프로파일 " + activeProfiles
                            + " 에 허용 목록 " + BOOT_ALLOWED_PROFILES + " 이 없다. "
                            + "pg.gateway.type=fake 는 smoke/benchmark 스택이나 pg 통합 테스트(test 프로파일)에서만 허용된다."
            );
        }
    }

    @Override
    public boolean supports(PgVendorType vendorType) {
        // Fake 는 TOSS / NICEPAY 어느 쪽이든 대응 — vendor 분기 없이 happy path 만 반환
        return vendorType == PgVendorType.TOSS || vendorType == PgVendorType.NICEPAY;
    }

    @Override
    public PgConfirmResult confirm(PgConfirmRequest request) {
        // 실측 시나리오는 중복 승인 판정보다 먼저 본다 — 재시도 자기루프로 같은 주문이 돌아올 때마다
        // 다시 실패해야 시도 횟수가 소진되고 격리까지 도달하기 때문이다.
        applyDrillScenario(request);

        if (processedOrders.containsKey(request.orderId())) {
            return handleDuplicateConfirm(request);
        }

        long latencyMillis = simulateVendorLatency();
        boolean inject = failRate > 0.0 && ThreadLocalRandom.current().nextDouble() < failRate;

        tossApiMetrics.recordTossApiCall(
                "confirm", latencyMillis, inject ? CallOutcome.FAILURE : CallOutcome.SUCCESS);

        if (inject) {
            // 확정 실패는 예외 throw 로만 신호된다(PgVendorCallService 가 confirm() 정상 반환을
            // 항상 Success 로 감싸므로). NonRetryable → handleDefinitiveFailure → payment FAILED 경로.
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NON_RETRYABLE_ERROR,
                    () -> "fake 주입 실패(데모 부하) orderId=" + request.orderId()
                            + " paymentKey=" + maskKey(request.paymentKey()));
            throw PgGatewayNonRetryableException.of("FAKE_INJECTED_FAILURE (데모 부하)");
        }

        // approvedAtRaw 는 ISO-8601 OffsetDateTime(UTC) 문자열로 ConfirmedEventPayload.approved() 에 주입된다.
        String approvedAtRaw = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)).toString();
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS,
                request.paymentKey(),
                request.orderId(),
                request.amount(),
                LocalDateTime.now(clock),
                null,
                approvedAtRaw
        );

        // putIfAbsent 로 첫 호출 판정을 atomic 하게 기록 — self-loop 병렬 재호출 race 방지.
        // 이미 다른 스레드가 먼저 기록했다면(레이스에서 패배) 그 결과를 기준으로 duplicate 분기한다.
        PgConfirmResult winner = processedOrders.putIfAbsent(request.orderId(), result);
        if (winner != null) {
            return handleDuplicateConfirm(request);
        }

        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_SUCCESS,
                () -> "fake orderId=" + request.orderId()
                        + " paymentKey=" + maskKey(request.paymentKey())
                        + " amount=" + request.amount());
        return result;
    }

    /**
     * paymentKey 접두어로 고른 실측 시나리오를 적용한다.
     * 해당 접두어면 그 경로의 예외를 던지고, 아니면 아무 것도 하지 않아 기존 승인 흐름으로 넘어간다.
     */
    private void applyDrillScenario(PgConfirmRequest request) {
        String paymentKey = request.paymentKey();
        if (paymentKey == null) {
            return;
        }

        if (paymentKey.startsWith(DRILL_FAIL_PREFIX)) {
            recordDrillFailure(request, EventType.PG_VENDOR_NON_RETRYABLE_ERROR, "확정 실패");
            throw PgGatewayNonRetryableException.of(
                    "DRILL_DEFINITIVE_FAILURE orderId=" + request.orderId());
        }

        if (paymentKey.startsWith(DRILL_RETRY_PREFIX)) {
            recordDrillFailure(request, EventType.PG_VENDOR_RETRYABLE_ERROR, "재시도 가능 실패");
            throw PgGatewayRetryableException.of(
                    "DRILL_RETRYABLE_FAILURE orderId=" + request.orderId());
        }

        if (paymentKey.startsWith(DRILL_FLAKY_PREFIX)) {
            int attempt = drillFlakyAttempts
                    .computeIfAbsent(request.orderId(), key -> new AtomicInteger())
                    .incrementAndGet();
            if (attempt <= DRILL_FLAKY_FAILURES_BEFORE_SUCCESS) {
                recordDrillFailure(request, EventType.PG_VENDOR_RETRYABLE_ERROR,
                        "자가 회복 전 " + attempt + "회차 실패");
                throw PgGatewayRetryableException.of(
                        "DRILL_FLAKY_FAILURE attempt=" + attempt + " orderId=" + request.orderId());
            }
        }
    }

    /**
     * 시나리오 실패도 실제 벤더 호출처럼 응답 시간과 실패 횟수를 남긴다 —
     * 그래야 벤더 호출량·응답시간 패널이 실측한 그대로 움직인다.
     */
    private void recordDrillFailure(PgConfirmRequest request, EventType eventType, String reason) {
        long latencyMillis = simulateVendorLatency();
        tossApiMetrics.recordTossApiCall("confirm", latencyMillis, CallOutcome.FAILURE);
        LogFmt.warn(log, LogDomain.PG_VENDOR, eventType,
                () -> "실측 시나리오 주입 — " + reason
                        + " orderId=" + request.orderId()
                        + " paymentKey=" + maskKey(request.paymentKey()));
    }

    /**
     * 동일 paymentKey(orderId) 재호출 — 실 벤더(Toss ALREADY_PROCESSED_PAYMENT)의 중복 승인 응답 모사.
     * {@link com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.TossPaymentGatewayStrategy}
     * 와 동일하게 이벤트 발행 후 예외를 throw하는 순서를 따른다(이중 신호).
     */
    private PgConfirmResult handleDuplicateConfirm(PgConfirmRequest request) {
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DUPLICATE_HANDLED,
                () -> "fake orderId=" + request.orderId() + " — ALREADY_PROCESSED_PAYMENT DuplicateApprovalDetectedEvent 발행");
        applicationEventPublisher.publishEvent(new DuplicateApprovalDetectedEvent(
                request.orderId(), request.amount(), request.paymentKey(),
                ALREADY_PROCESSED_REASON_CODE, request.vendorType()));
        throw PgGatewayDuplicateHandledException.of(
                "ALREADY_PROCESSED_PAYMENT handled for orderId=" + request.orderId());
    }

    /**
     * 합성 벤더 RTT — [min, max] 범위 랜덤 sleep 후 경과 ms 반환.
     * 가상 스레드(VT) 워커에서 호출되므로 sleep 비용은 무해하며, 트레이스 span 과 toss latency 패널을
     * 실수치처럼 보이게 한다. min=max=0(기본)이면 sleep 없이 0 반환 — 기존 smoke happy-path 무영향.
     */
    private long simulateVendorLatency() {
        if (latencyMaxMillis <= 0) {
            return 0L;
        }
        long target = latencyMinMillis == latencyMaxMillis
                ? latencyMinMillis
                : ThreadLocalRandom.current().nextLong(latencyMinMillis, latencyMaxMillis + 1);
        if (target > 0) {
            try {
                Thread.sleep(target);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return target;
    }

    @Override
    public PgStatusResult getStatusByOrderId(String orderId) {
        // DuplicateApprovalHandler.handleDbExists 가 중복 승인 흡수 시 벤더 상태를 재조회하는 경로.
        // 처리된 orderId 라면 happy-path DONE 응답으로 흡수 경로를 완결시킨다 — amount 는 최초 confirm 과
        // 동일해야 한다(불일치 시 handleDbExists 가 QUARANTINED(AMOUNT_MISMATCH) 로 분기하기 때문).
        PgConfirmResult processed = processedOrders.get(orderId);
        if (processed != null) {
            return toStatusResult(processed);
        }

        // 처리 기록이 없는 orderId 는 시나리오 설계 오류 또는 예기치 못한 호출을 의미한다.
        // 계약은 코드로 표현돼야 한다 — null 반환 대신 명시적 예외로 즉시 실패.
        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NETWORK_ERROR,
                () -> "fake getStatusByOrderId 호출 감지 — 처리 기록 없는 orderId=" + orderId);
        throw new UnsupportedOperationException(
                "Fake strategy: 처리 기록 없는 orderId 의 getStatusByOrderId 는 호출되지 않아야 함. "
                        + "실제 복구 사이클은 TossPaymentGatewayStrategy / NicepayPaymentGatewayStrategy 를 사용하라."
        );
    }

    private PgStatusResult toStatusResult(PgConfirmResult confirmed) {
        return new PgStatusResult(
                confirmed.paymentKey(),
                confirmed.orderId(),
                PgPaymentStatus.DONE,
                confirmed.amount(),
                confirmed.approvedAt(),
                null
        );
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= FAKE_PAYMENT_KEY_PREFIX.length() + 4) {
            return key;
        }
        return key.substring(0, FAKE_PAYMENT_KEY_PREFIX.length() + 4) + "***";
    }
}
