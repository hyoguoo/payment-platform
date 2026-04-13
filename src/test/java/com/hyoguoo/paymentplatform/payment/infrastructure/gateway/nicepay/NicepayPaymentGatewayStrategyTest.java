package com.hyoguoo.paymentplatform.payment.infrastructure.gateway.nicepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentCancelResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.NicepayGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.NicepayPaymentResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NicepayPaymentGatewayStrategyTest {

    @Mock
    private NicepayGatewayInternalReceiver nicepayGatewayInternalReceiver;

    @InjectMocks
    private NicepayPaymentGatewayStrategy strategy;

    @Test
    @DisplayName("supports: NICEPAY 타입이면 true를 반환한다")
    void supports_NicepayType_ReturnsTrue() {
        assertThat(strategy.supports(PaymentGatewayType.NICEPAY)).isTrue();
    }

    @Test
    @DisplayName("supports: TOSS 타입이면 false를 반환한다")
    void supports_TossType_ReturnsFalse() {
        assertThat(strategy.supports(PaymentGatewayType.TOSS)).isFalse();
    }

    @Test
    @DisplayName("confirm: resultCode=0000이면 SUCCESS 상태와 approvedAt을 반환한다")
    void confirm_Success_ReturnsDoneStatus() throws Exception {
        // given
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                "order-001", "tid-001", BigDecimal.valueOf(10000), PaymentGatewayType.NICEPAY
        );
        NicepayPaymentResponse response = NicepayPaymentResponse.builder()
                .tid("tid-001")
                .orderId("order-001")
                .amount(BigDecimal.valueOf(10000))
                .status("paid")
                .resultCode("0000")
                .resultMsg("정상 처리되었습니다.")
                .paidAt("2026-04-13T12:00:00.000+0900")
                .build();
        given(nicepayGatewayInternalReceiver.confirmPayment(any())).willReturn(response);

        // when
        PaymentConfirmResult result = strategy.confirm(request);

        // then
        assertThat(result.status()).isEqualTo(PaymentConfirmResultStatus.SUCCESS);
        assertThat(result.approvedAt()).isNotNull();
        assertThat(result.paymentKey()).isEqualTo("tid-001");
        assertThat(result.orderId()).isEqualTo("order-001");
    }

    @Test
    @DisplayName("getStatus: NicePay status=paid이면 PaymentStatus.DONE을 반환한다")
    void getStatus_PaidStatus_ReturnsDone() {
        // given
        NicepayPaymentResponse response = NicepayPaymentResponse.builder()
                .tid("tid-001")
                .orderId("order-001")
                .amount(BigDecimal.valueOf(10000))
                .status("paid")
                .resultCode("0000")
                .resultMsg("정상 처리되었습니다.")
                .paidAt("2026-04-13T12:00:00.000+0900")
                .build();
        given(nicepayGatewayInternalReceiver.getPaymentInfoByTid(anyString())).willReturn(response);

        // when
        PaymentStatusResult result = strategy.getStatus("tid-001", PaymentGatewayType.NICEPAY);

        // then
        assertThat(result.status()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("getStatus: NicePay status=failed이면 PaymentStatus.ABORTED를 반환한다")
    void getStatus_FailedStatus_ReturnsAborted() {
        // given
        NicepayPaymentResponse response = NicepayPaymentResponse.builder()
                .tid("tid-001")
                .orderId("order-001")
                .amount(BigDecimal.valueOf(10000))
                .status("failed")
                .resultCode("A000")
                .resultMsg("결제 실패")
                .build();
        given(nicepayGatewayInternalReceiver.getPaymentInfoByTid(anyString())).willReturn(response);

        // when
        PaymentStatusResult result = strategy.getStatus("tid-001", PaymentGatewayType.NICEPAY);

        // then
        assertThat(result.status()).isEqualTo(PaymentStatus.ABORTED);
    }

    @Test
    @DisplayName("getStatusByOrderId: orderId로 정상 조회하면 상태가 매핑되어 반환된다")
    void getStatusByOrderId_Success_ReturnsMapped() throws Exception {
        // given
        NicepayPaymentResponse response = NicepayPaymentResponse.builder()
                .tid("tid-001")
                .orderId("order-001")
                .amount(BigDecimal.valueOf(10000))
                .status("paid")
                .resultCode("0000")
                .resultMsg("정상 처리되었습니다.")
                .paidAt("2026-04-13T12:00:00.000+0900")
                .build();
        given(nicepayGatewayInternalReceiver.getPaymentInfoByOrderId(anyString())).willReturn(response);

        // when
        PaymentStatusResult result = strategy.getStatusByOrderId("order-001", PaymentGatewayType.NICEPAY);

        // then
        assertThat(result.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(result.paymentKey()).isEqualTo("tid-001");
        assertThat(result.orderId()).isEqualTo("order-001");
    }

    @Test
    @DisplayName("cancel: 정상 취소 흐름에서 SUCCESS 상태를 반환한다")
    void cancel_Success_ReturnsCancelResult() {
        // given
        PaymentCancelRequest request = new PaymentCancelRequest(
                "tid-001", "단순 변심", BigDecimal.valueOf(10000), PaymentGatewayType.NICEPAY
        );
        NicepayPaymentResponse response = NicepayPaymentResponse.builder()
                .tid("tid-001")
                .orderId("order-001")
                .amount(BigDecimal.valueOf(10000))
                .status("cancelled")
                .resultCode("0000")
                .resultMsg("취소 성공")
                .paidAt("2026-04-13T12:00:00.000+0900")
                .build();
        given(nicepayGatewayInternalReceiver.cancelPayment(any())).willReturn(response);

        // when
        PaymentCancelResult result = strategy.cancel(request);

        // then
        assertThat(result.status()).isEqualTo(PaymentCancelResultStatus.SUCCESS);
        assertThat(result.paymentKey()).isEqualTo("tid-001");
    }
}
