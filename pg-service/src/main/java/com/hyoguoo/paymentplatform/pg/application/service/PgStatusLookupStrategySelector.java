package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * K14: vendorType 기반 PgStatusLookupPort 전략 선택기.
 *
 * <p>production 모드: Toss + NicePay 두 빈 모두 등록됨.
 * smoke 모드(pg.gateway.type=fake): FakePgGatewayStrategy 단일 빈(supports=TOSS|NICEPAY).
 *
 * <p>ADR-21 / ADR-30: pg-service 내부 전략 분기 — payment-service 비공개.
 */
@Service
public class PgStatusLookupStrategySelector {

    private final List<PgStatusLookupPort> ports;

    public PgStatusLookupStrategySelector(List<PgStatusLookupPort> ports) {
        this.ports = ports;
    }

    /**
     * vendorType 에 대응하는 PgStatusLookupPort 구현체를 반환한다.
     *
     * @param vendorType 벤더 구분 타입
     * @return 해당 벤더를 지원하는 PgStatusLookupPort 구현체
     * @throws IllegalStateException 지원하는 구현체가 없을 경우
     */
    public PgStatusLookupPort select(PgVendorType vendorType) {
        return ports.stream()
                .filter(port -> port.supports(vendorType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No PgStatusLookupPort supports vendorType=" + vendorType));
    }
}
