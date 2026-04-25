package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
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
 * K14 RED: PgStatusLookupStrategySelector — vendorType 기반 전략 분기 단위 테스트.
 *
 * <p>불변식:
 * <ul>
 *   <li>TOSS → Toss strategy 반환</li>
 *   <li>NICEPAY → Nicepay strategy 반환</li>
 *   <li>미지원 벤더 → IllegalStateException throw</li>
 * </ul>
 */
@DisplayName("PgStatusLookupStrategySelector")
class PgStatusLookupStrategySelectorTest {

    private FakePgGatewayAdapterToss tossAdapter;
    private FakePgGatewayAdapterNicepay nicepayAdapter;
    private PgStatusLookupStrategySelector selector;

    @BeforeEach
    void setUp() {
        tossAdapter = new FakePgGatewayAdapterToss();      // supports TOSS only
        nicepayAdapter = new FakePgGatewayAdapterNicepay(); // supports NICEPAY only
        selector = new PgStatusLookupStrategySelector(List.of(tossAdapter, nicepayAdapter));
    }

    // -----------------------------------------------------------------------
    // TC1: TOSS → Toss strategy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("select — TOSS vendorType 이면 Toss strategy 반환")
    void select_WhenToss_ShouldReturnTossStrategy() {
        PgStatusLookupPort selected = selector.select(PgVendorType.TOSS);
        assertThat(selected).isSameAs(tossAdapter);
    }

    // -----------------------------------------------------------------------
    // TC2: NICEPAY → Nicepay strategy
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("select — NICEPAY vendorType 이면 Nicepay strategy 반환")
    void select_WhenNicepay_ShouldReturnNicepayStrategy() {
        PgStatusLookupPort selected = selector.select(PgVendorType.NICEPAY);
        assertThat(selected).isSameAs(nicepayAdapter);
    }

    // -----------------------------------------------------------------------
    // TC3: 빈 목록 → IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("select — 지원하는 strategy 없으면 IllegalStateException")
    void select_WhenNoMatch_ShouldThrowIllegalStateException() {
        PgStatusLookupStrategySelector emptySelector = new PgStatusLookupStrategySelector(List.of());
        assertThatThrownBy(() -> emptySelector.select(PgVendorType.NICEPAY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NICEPAY");
    }
}
