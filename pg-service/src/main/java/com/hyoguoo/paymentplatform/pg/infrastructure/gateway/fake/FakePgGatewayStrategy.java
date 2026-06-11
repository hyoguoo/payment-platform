package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics.CallOutcome;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fake PG 벤더 전략 — happy-path 통합 스모크 전용.
 *
 * <p><b>production 환경에서 절대 활성화 금지.</b>
 * 활성화 조건: {@code pg.gateway.type=fake} (smoke 프로파일에서만 override).
 * Toss(default), NicePay 와 상호 배타이므로 정상 환경에선 빈 등록되지 않는다.
 *
 * <p>해피 패스 전용 스트래티지:
 * <ul>
 *   <li>paymentKey 접두어 무관 → 항상 APPROVED 반환</li>
 *   <li>approvedAt 은 {@link Clock} 기반 현재 시각</li>
 *   <li>브라우저 PG SDK 호출 없이 confirm 요청만으로 5-service chain smoke 가 완결되도록 설계</li>
 * </ul>
 *
 * <p>smoke 목적상 FAIL / QUARANTINE 경로는 지원하지 않는다 — 보상 경로 검증은 Phase 4
 * Toxiproxy 시나리오로 정돈되어 주입된다.
 *
 * @see com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.TossPaymentGatewayStrategy 프로덕션 Toss 구현
 * @see com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.NicepayPaymentGatewayStrategy 프로덕션 NicePay 구현
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "pg.gateway.type", havingValue = "fake")
public class FakePgGatewayStrategy implements PgStatusLookupPort, PgConfirmPort {

    private static final String FAKE_PAYMENT_KEY_PREFIX = "fake-";

    private final Clock clock;
    private final TossApiMetrics tossApiMetrics;

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
            @Value("${pg.gateway.fake.fail-rate:0.0}") double failRate,
            @Value("${pg.gateway.fake.latency-min-millis:0}") long latencyMinMillis,
            @Value("${pg.gateway.fake.latency-max-millis:0}") long latencyMaxMillis
    ) {
        this.clock = clock;
        this.tossApiMetrics = tossApiMetrics;
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
    }

    @Override
    public boolean supports(PgVendorType vendorType) {
        // Fake 는 TOSS / NICEPAY 어느 쪽이든 대응 — vendor 분기 없이 happy path 만 반환
        return vendorType == PgVendorType.TOSS || vendorType == PgVendorType.NICEPAY;
    }

    @Override
    public PgConfirmResult confirm(PgConfirmRequest request) {
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

        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_SUCCESS,
                () -> "fake orderId=" + request.orderId()
                        + " paymentKey=" + maskKey(request.paymentKey())
                        + " amount=" + request.amount());

        // approvedAtRaw 는 ISO-8601 OffsetDateTime(UTC) 문자열로 ConfirmedEventPayload.approved() 에 주입된다.
        String approvedAtRaw = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)).toString();
        return new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS,
                request.paymentKey(),
                request.orderId(),
                request.amount(),
                LocalDateTime.now(clock),
                null,
                approvedAtRaw
        );
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
        // Fake 전략은 smoke happy-path 전용이므로 getStatusByOrderId 는 호출될 수 없다.
        // DuplicateApprovalHandler / PgFinalConfirmationGate(복구 사이클) 에서만 호출되는 경로로,
        // smoke 환경에서 해당 경로가 트리거됐다면 시나리오 설계 오류 또는 예기치 못한 중복 승인 발생을 의미한다.
        // 계약은 코드로 표현돼야 한다 — null 반환 대신 명시적 예외로 즉시 실패.
        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NETWORK_ERROR,
                () -> "fake getStatusByOrderId 호출 감지 — smoke 경로에서 예상되지 않은 복구 사이클 진입. orderId=" + orderId);
        throw new UnsupportedOperationException(
                "Fake strategy: getStatusByOrderId 는 smoke 경로에서 호출되지 않아야 함. "
                        + "실제 복구 사이클은 TossPaymentGatewayStrategy / NicepayPaymentGatewayStrategy 를 사용하라."
        );
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= FAKE_PAYMENT_KEY_PREFIX.length() + 4) {
            return key;
        }
        return key.substring(0, FAKE_PAYMENT_KEY_PREFIX.length() + 4) + "***";
    }
}
