package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * vendorType 기반 PgConfirmPort 전략 선택기.
 *
 * <p>production 모드: Toss + NicePay 두 빈이 모두 등록되고 vendorType 으로 분기한다.
 * smoke 모드(pg.gateway.type=fake): FakePgGatewayStrategy 단일 빈이 모든 벤더를 받는다.
 * pg-service 내부 전략 분기이므로 payment-service 에는 노출하지 않는다.
 */
@Service
public class PgConfirmStrategySelector {

    private final List<PgConfirmPort> ports;

    public PgConfirmStrategySelector(List<PgConfirmPort> ports) {
        this.ports = ports;
    }

    /**
     * vendorType 에 대응하는 PgConfirmPort 구현체를 반환한다.
     *
     * @param vendorType 벤더 구분 타입
     * @return 해당 벤더를 지원하는 PgConfirmPort 구현체
     * @throws IllegalStateException 지원하는 구현체가 없을 경우
     */
    public PgConfirmPort select(PgVendorType vendorType) {
        return ports.stream()
                .filter(port -> port.supports(vendorType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No PgConfirmPort supports vendorType=" + vendorType));
    }
}
