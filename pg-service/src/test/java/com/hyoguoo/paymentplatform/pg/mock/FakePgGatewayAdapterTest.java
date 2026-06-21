package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgFailureInfo;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
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
                PgConfirmResultStatus.SUCCESS, "pk", "order-3", BigDecimal.TEN, null, null, null);
        fake.setConfirmResult("order-3", result);
        fake.confirm(new PgConfirmRequest("order-3", "pk", BigDecimal.TEN, PgVendorType.NICEPAY));

        fake.reset();

        assertThat(fake.getConfirmCallCount()).isEqualTo(0);
        assertThatThrownBy(() -> fake.confirm(
                new PgConfirmRequest("order-3", "pk", BigDecimal.TEN, PgVendorType.TOSS)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enableIdempotentDuplicate_첫호출은_SUCCESS_재호출은_DuplicateHandledException() {
        fake.enableIdempotentDuplicate();
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, "pk-idem", "order-idem", BigDecimal.valueOf(15000),
                null, null, null);
        fake.setConfirmResult("order-idem", result);
        PgConfirmRequest request =
                new PgConfirmRequest("order-idem", "pk-idem", BigDecimal.valueOf(15000), PgVendorType.TOSS);

        PgConfirmResult firstCall = fake.confirm(request);

        assertThat(firstCall).isEqualTo(result);
        assertThatThrownBy(() -> fake.confirm(request))
                .isInstanceOf(PgGatewayDuplicateHandledException.class);
        assertThat(fake.getConfirmCallCount()).isEqualTo(2);
    }

    @Test
    void enableIdempotentDuplicate_처리된_orderId는_명시적_설정_없이도_DONE_상태를_합성한다() {
        fake.enableIdempotentDuplicate();
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, "pk-idem2", "order-idem2", BigDecimal.valueOf(7000),
                null, null, null);
        fake.setConfirmResult("order-idem2", result);
        fake.confirm(new PgConfirmRequest("order-idem2", "pk-idem2", BigDecimal.valueOf(7000), PgVendorType.TOSS));

        PgStatusResult statusResult = fake.getStatusByOrderId("order-idem2");

        assertThat(statusResult.status()).isEqualTo(PgPaymentStatus.DONE);
        assertThat(statusResult.amount()).isEqualTo(BigDecimal.valueOf(7000));
        assertThat(statusResult.orderId()).isEqualTo("order-idem2");
    }

    @Test
    void enableIdempotentDuplicate_throwOnConfirm_일회성_주입이_우선한다() {
        fake.enableIdempotentDuplicate();
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, "pk-idem3", "order-idem3", BigDecimal.valueOf(3000),
                null, null, null);
        fake.setConfirmResult("order-idem3", result);
        fake.throwOnConfirm(PgGatewayRetryableException.of("first-call-timeout"));
        PgConfirmRequest request =
                new PgConfirmRequest("order-idem3", "pk-idem3", BigDecimal.valueOf(3000), PgVendorType.TOSS);

        assertThatThrownBy(() -> fake.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class)
                .hasMessage("first-call-timeout");

        // throwOnConfirm 은 일회성 — 그 다음 호출부터 멱등 모드 happy-path 가 정상 적용된다.
        PgConfirmResult secondCall = fake.confirm(request);
        assertThat(secondCall).isEqualTo(result);
    }

    @Test
    void reset_호출_후_멱등_모드도_초기화된다() {
        fake.enableIdempotentDuplicate();
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, "pk-idem4", "order-idem4", BigDecimal.valueOf(2000),
                null, null, null);
        fake.setConfirmResult("order-idem4", result);
        fake.confirm(new PgConfirmRequest("order-idem4", "pk-idem4", BigDecimal.valueOf(2000), PgVendorType.TOSS));

        fake.reset();
        fake.setConfirmResult("order-idem4", result);
        PgConfirmResult afterReset = fake.confirm(
                new PgConfirmRequest("order-idem4", "pk-idem4", BigDecimal.valueOf(2000), PgVendorType.TOSS));

        assertThat(afterReset).isEqualTo(result);
    }
}
