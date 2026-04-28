package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapterToss;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapterNicepay;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgConfirmStrategySelector 단위 테스트 — vendorType 기반 전략 분기 검증.
 *
 * <p>불변식:
 * <ul>
 *   <li>TOSS → Toss strategy 반환</li>
 *   <li>NICEPAY → Nicepay strategy 반환</li>
 *   <li>미지원 벤더 → IllegalStateException throw</li>
 * </ul>
 */
@DisplayName("PgConfirmStrategySelector")
class PgConfirmStrategySelectorTest {

    private FakePgGatewayAdapterToss tossAdapter;
    private FakePgGatewayAdapterNicepay nicepayAdapter;
    private PgConfirmStrategySelector selector;

    @BeforeEach
    void setUp() {
        tossAdapter = new FakePgGatewayAdapterToss();      // supports TOSS only
        nicepayAdapter = new FakePgGatewayAdapterNicepay(); // supports NICEPAY only
        selector = new PgConfirmStrategySelector(List.of(tossAdapter, nicepayAdapter));
    }

    // -----------------------------------------------------------------------
    // TC1: TOSS → Toss strategy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("select — TOSS vendorType 이면 Toss strategy 반환")
    void select_WhenToss_ShouldReturnTossStrategy() {
        PgConfirmPort selected = selector.select(PgVendorType.TOSS);
        assertThat(selected).isSameAs(tossAdapter);
    }

    // -----------------------------------------------------------------------
    // TC2: NICEPAY → Nicepay strategy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("select — NICEPAY vendorType 이면 Nicepay strategy 반환")
    void select_WhenNicepay_ShouldReturnNicepayStrategy() {
        PgConfirmPort selected = selector.select(PgVendorType.NICEPAY);
        assertThat(selected).isSameAs(nicepayAdapter);
    }

    // -----------------------------------------------------------------------
    // TC3: 빈 목록 또는 미지원 벤더 → IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("select — 지원하는 strategy 없으면 IllegalStateException")
    void select_WhenNoMatch_ShouldThrowIllegalStateException() {
        PgConfirmStrategySelector emptySelector = new PgConfirmStrategySelector(List.of());
        assertThatThrownBy(() -> emptySelector.select(PgVendorType.TOSS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS");
    }
}
