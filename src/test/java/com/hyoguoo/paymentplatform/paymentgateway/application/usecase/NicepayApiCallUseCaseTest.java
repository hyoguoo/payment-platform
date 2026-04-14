package com.hyoguoo.paymentplatform.paymentgateway.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.NicepayConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.NicepayOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.NicepayPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("NicepayApiCallUseCase 테스트")
class NicepayApiCallUseCaseTest {

    private NicepayOperator mockNicepayOperator;
    private NicepayApiCallUseCase nicepayApiCallUseCase;

    @BeforeEach
    void setUp() {
        mockNicepayOperator = Mockito.mock(NicepayOperator.class);
        nicepayApiCallUseCase = new NicepayApiCallUseCase(mockNicepayOperator);
    }

    @Test
    @DisplayName("executeConfirmPayment - NicepayOperator에 위임하고 결과를 반환한다")
    void executeConfirmPayment_DelegatesToOperator() throws PaymentGatewayApiException {
        // given
        NicepayConfirmCommand command = NicepayConfirmCommand.builder()
                .tid("tid-123")
                .amount(BigDecimal.valueOf(10000))
                .build();
        NicepayPaymentInfo expectedInfo = createPaymentInfo("tid-123", "0000");

        given(mockNicepayOperator.confirmPayment(command)).willReturn(expectedInfo);

        // when
        NicepayPaymentInfo result = nicepayApiCallUseCase.executeConfirmPayment(command);

        // then
        assertThat(result).isEqualTo(expectedInfo);
        then(mockNicepayOperator).should(times(1)).confirmPayment(command);
    }

    @Test
    @DisplayName("executeConfirmPayment - PaymentGatewayApiException 발생 시 그대로 전파한다")
    void executeConfirmPayment_PropagatesApiException() throws PaymentGatewayApiException {
        // given
        NicepayConfirmCommand command = NicepayConfirmCommand.builder()
                .tid("tid-123")
                .amount(BigDecimal.valueOf(10000))
                .build();

        given(mockNicepayOperator.confirmPayment(command))
                .willThrow(PaymentGatewayApiException.of("3011", "카드 한도 초과"));

        // when & then
        assertThatThrownBy(() -> nicepayApiCallUseCase.executeConfirmPayment(command))
                .isInstanceOf(PaymentGatewayApiException.class);
    }

    @Test
    @DisplayName("getPaymentInfoByTid - tid 기반 조회를 위임하고 결과를 반환한다")
    void getPaymentInfoByTid_DelegatesToOperator() {
        // given
        NicepayPaymentInfo expectedInfo = createPaymentInfo("tid-123", "0000");
        given(mockNicepayOperator.getPaymentInfoByTid("tid-123")).willReturn(expectedInfo);

        // when
        NicepayPaymentInfo result = nicepayApiCallUseCase.getPaymentInfoByTid("tid-123");

        // then
        assertThat(result).isEqualTo(expectedInfo);
        then(mockNicepayOperator).should(times(1)).getPaymentInfoByTid("tid-123");
    }

    @Test
    @DisplayName("getPaymentInfoByOrderId - orderId 기반 조회를 위임하고 결과를 반환한다")
    void getPaymentInfoByOrderId_DelegatesToOperator() throws PaymentGatewayApiException {
        // given
        NicepayPaymentInfo expectedInfo = createPaymentInfo("tid-123", "0000");
        given(mockNicepayOperator.getPaymentInfoByOrderId("order-123")).willReturn(expectedInfo);

        // when
        NicepayPaymentInfo result = nicepayApiCallUseCase.getPaymentInfoByOrderId("order-123");

        // then
        assertThat(result).isEqualTo(expectedInfo);
        then(mockNicepayOperator).should(times(1)).getPaymentInfoByOrderId("order-123");
    }

    @Test
    @DisplayName("getPaymentInfoByOrderId - PaymentGatewayApiException 발생 시 그대로 전파한다")
    void getPaymentInfoByOrderId_PropagatesApiException() throws PaymentGatewayApiException {
        // given
        given(mockNicepayOperator.getPaymentInfoByOrderId("order-123"))
                .willThrow(PaymentGatewayApiException.of("A246", "일시적 오류"));

        // when & then
        assertThatThrownBy(() -> nicepayApiCallUseCase.getPaymentInfoByOrderId("order-123"))
                .isInstanceOf(PaymentGatewayApiException.class);
    }

    @Test
    @DisplayName("executeCancelPayment - NicepayOperator에 위임하고 결과를 반환한다")
    void executeCancelPayment_DelegatesToOperator() {
        // given
        NicepayCancelCommand command = NicepayCancelCommand.builder()
                .tid("tid-123")
                .orderId("order-123")
                .reason("고객 요청")
                .build();
        NicepayPaymentInfo expectedInfo = createPaymentInfo("tid-123", "0000");

        given(mockNicepayOperator.cancelPayment(command)).willReturn(expectedInfo);

        // when
        NicepayPaymentInfo result = nicepayApiCallUseCase.executeCancelPayment(command);

        // then
        assertThat(result).isEqualTo(expectedInfo);
        then(mockNicepayOperator).should(times(1)).cancelPayment(command);
    }

    private NicepayPaymentInfo createPaymentInfo(String tid, String resultCode) {
        return NicepayPaymentInfo.builder()
                .tid(tid)
                .orderId("order-123")
                .amount(BigDecimal.valueOf(10000))
                .status("paid")
                .resultCode(resultCode)
                .resultMsg("성공")
                .paidAt("2026-04-14T12:00:00.000+0900")
                .build();
    }
}
