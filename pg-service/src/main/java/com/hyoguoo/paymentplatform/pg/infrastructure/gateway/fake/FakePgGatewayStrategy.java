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
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fake PG 벤더 전략 — phase-3-integration-smoke 전용.
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

    public FakePgGatewayStrategy(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    void warnActivation() {
        // 의도적 평문 로그 — LogFmt 의 key=value 포맷과 달리 시각적 경고 배너.
        // 기동 로그에서 즉시 눈에 띄어야 prod 오염 사고를 방지할 수 있다.
        log.warn("╔══════════════════════════════════════════════════════════════╗");
        log.warn("║  FAKE PG STRATEGY ACTIVE — SMOKE PROFILE ONLY                ║");
        log.warn("║  PRODUCTION ENVIRONMENT MUST NOT ENABLE pg.gateway.type=fake ║");
        log.warn("╚══════════════════════════════════════════════════════════════╝");
    }

    @Override
    public boolean supports(PgVendorType vendorType) {
        // Fake 는 TOSS / NICEPAY 어느 쪽이든 대응 — vendor 분기 없이 happy path 만 반환
        return vendorType == PgVendorType.TOSS || vendorType == PgVendorType.NICEPAY;
    }

    @Override
    public PgConfirmResult confirm(PgConfirmRequest request) {
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_SUCCESS,
                () -> "fake orderId=" + request.orderId()
                        + " paymentKey=" + maskKey(request.paymentKey())
                        + " amount=" + request.amount());

        return new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS,
                request.paymentKey(),
                request.orderId(),
                request.amount(),
                LocalDateTime.now(clock),
                null
        );
    }

    @Override
    public PgStatusResult getStatusByOrderId(String orderId) {
        // smoke happy path 상에서는 호출되지 않는다.
        // 호출됐다는 것은 복구 사이클 진입을 의미 — 운영 지표로 감시 대상.
        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NETWORK_ERROR,
                () -> "fake getStatusByOrderId called — 복구 경로 진입 (smoke 예상 밖 경로) orderId=" + orderId);
        return null;
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= FAKE_PAYMENT_KEY_PREFIX.length() + 4) {
            return key;
        }
        return key.substring(0, FAKE_PAYMENT_KEY_PREFIX.length() + 4) + "***";
    }
}
