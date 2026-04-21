package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgFailureInfo;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FakePgGatewayAdapter 스모크 테스트.
 * 실제 HTTP 없이 confirm 결과 주입·예외 주입·호출 횟수 추적을 검증한다.
 */
class FakePgGatewayAdapterTest {

    private FakePgGatewayAdapter fake;

    @BeforeEach
    void setUp() {
        fake = new FakePgGatewayAdapter();
    }

    @Test
    void confirm_설정된_결과를_반환한다() {
        PgConfirmResult expected = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS,
                "pk-test",
                "order-1",
                BigDecimal.valueOf(10000),
                null,
                null);
        fake.setConfirmResult("order-1", expected);

        PgConfirmRequest request = new PgConfirmRequest("order-1", "pk-test", BigDecimal.valueOf(10000), PgVendorType.TOSS);
        PgConfirmResult actual = fake.confirm(request);

        assertThat(actual).isEqualTo(expected);
        assertThat(fake.getConfirmCallCount()).isEqualTo(1);
    }

    @Test
    void throwOnConfirm_주입_시_예외를_던진다() {
        fake.throwOnConfirm(PgGatewayRetryableException.of("timeout"));

        PgConfirmRequest request = new PgConfirmRequest("order-2", "pk-test", BigDecimal.valueOf(5000), PgVendorType.TOSS);

        assertThatThrownBy(() -> fake.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class)
                .hasMessage("timeout");
        assertThat(fake.getConfirmCallCount()).isEqualTo(1);
    }

    @Test
    void reset_호출_후_상태가_초기화된다() {
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, "pk", "order-3", BigDecimal.TEN, null, null);
        fake.setConfirmResult("order-3", result);
        fake.confirm(new PgConfirmRequest("order-3", "pk", BigDecimal.TEN, PgVendorType.NICEPAY));

        fake.reset();

        assertThat(fake.getConfirmCallCount()).isEqualTo(0);
        assertThatThrownBy(() -> fake.confirm(
                new PgConfirmRequest("order-3", "pk", BigDecimal.TEN, PgVendorType.TOSS)))
                .isInstanceOf(IllegalStateException.class);
    }
}
