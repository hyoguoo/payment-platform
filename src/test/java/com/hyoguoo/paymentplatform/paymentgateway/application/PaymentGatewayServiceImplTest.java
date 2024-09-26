package com.hyoguoo.paymentplatform.paymentgateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.mock.FakeTossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentGatewayServiceImplTest {

    private PaymentGatewayServiceImpl paymentGatewayService;
    private FakeTossOperator fakeTossOperator;

    static Stream<Arguments> provideRetryableErrorCodes() {
        return Stream.of(
                Arguments.of("PROVIDER_ERROR", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
                Arguments.of("FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
                        "결제가 완료되지 않았어요. 다시 시도해주세요."),
                Arguments.of("FAILED_INTERNAL_SYSTEM_PROCESSING",
                        "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."),
                Arguments.of("UNKNOWN_PAYMENT_ERROR", "결제에 실패했어요. 같은 문제가 반복된다면 은행이나 카드사로 문의해주세요.")
        );
    }

    static Stream<Arguments> provideNonRetryableErrorCodes() {
        return Stream.of(
                Arguments.of("INVALID_CARD_NUMBER", "카드번호를 다시 확인해주세요."),
                Arguments.of("EXCEED_MAX_CARD_INSTALLMENT_PLAN", "설정 가능한 최대 할부 개월 수를 초과했습니다."),
                Arguments.of("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."),
                Arguments.of("UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."),
                Arguments.of("FORBIDDEN_REQUEST", "허용되지 않은 요청입니다.")
        );
    }

    private static TossConfirmCommand createDefaultConfirmCommand() {
        return TossConfirmCommand.builder()
                .orderId("order123")
                .amount(new BigDecimal(10000))
                .paymentKey("validPaymentKey")
                .build();
    }

    @BeforeEach
    void setUp() {
        fakeTossOperator = new FakeTossOperator();
        paymentGatewayService = new PaymentGatewayServiceImpl(fakeTossOperator);
    }

    @Test
    @DisplayName("결제 승인 성공 시 SUCCESS 상태와 함께 결제 정보를 반환한다.")
    void confirmPayment_Success() {
        // given
        TossConfirmCommand tossConfirmCommand = createDefaultConfirmCommand();
        String idempotencyKey = "some-key";
        fakeTossOperator.setErrorCode(false, null, null);

        // when
        TossPaymentInfo result = paymentGatewayService.confirmPayment(
                tossConfirmCommand,
                idempotencyKey
        );

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.SUCCESS);
        assertThat(result.getPaymentDetails()).isNotNull();
        assertThat(result.getPaymentFailure()).isNull();
    }

    @ParameterizedTest
    @MethodSource("provideRetryableErrorCodes")
    @DisplayName("결제 승인 재시도가 가능한 실패 시 RETRYABLE_FAILURE 상태와 함께 결제 실패 정보를 반환한다.")
    void confirmPayment_RetryableFailure(String errorCodeString, String errorDescription) {
        // given
        TossConfirmCommand tossConfirmCommand = createDefaultConfirmCommand();

        String idempotencyKey = "some-key";
        fakeTossOperator.setErrorCode(true, errorCodeString, errorDescription);

        // when
        TossPaymentInfo result = paymentGatewayService.confirmPayment(
                tossConfirmCommand,
                idempotencyKey
        );

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.RETRYABLE_FAILURE);
        assertThat(result.getPaymentFailure().getCode()).isEqualTo(errorCodeString);
        assertThat(result.getPaymentFailure().getMessage()).isEqualTo(errorDescription);
    }

    @ParameterizedTest
    @MethodSource("provideNonRetryableErrorCodes")
    @DisplayName("결제 승인 재시도 불가능한 실패 시 NON_RETRYABLE_FAILURE 상태와 함께 결제 실패 정보를 반환한다.")
    void confirmPayment_NonRetryableFailure(String errorCodeString, String errorDescription) {
        // given
        TossConfirmCommand tossConfirmCommand = createDefaultConfirmCommand();

        String idempotencyKey = "some-key";
        fakeTossOperator.setErrorCode(true, errorCodeString, errorDescription);

        // when
        TossPaymentInfo result = paymentGatewayService.confirmPayment(
                tossConfirmCommand,
                idempotencyKey
        );

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE);
        assertThat(result.getPaymentFailure().getCode()).isEqualTo(errorCodeString);
        assertThat(result.getPaymentFailure().getMessage()).isEqualTo(errorDescription);
    }

    @Test
    @DisplayName("결제 정보를 성공적으로 조회한다.")
    void getPaymentResultByOrderId_Success() {
        // given
        String orderId = "order123";
        fakeTossOperator.setErrorCode(false, null, null);

        // when
        TossPaymentInfo result = paymentGatewayService.getPaymentResultByOrderId(orderId);

        // then
        assertThat(result.getOrderId()).isEqualTo(orderId);
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.SUCCESS);
        assertThat(result.getPaymentFailure()).isNull();
    }

    @Test
    @DisplayName("결제를 성공적으로 취소한다.")
    void cancelPayment_Success() {
        // given
        TossCancelCommand tossCancelCommand = TossCancelCommand.builder()
                .paymentKey("validPaymentKey")
                .cancelReason("사유")
                .build();
        String idempotencyKey = "some-key";
        fakeTossOperator.setErrorCode(false, null, null);

        // when
        TossPaymentInfo result = paymentGatewayService.cancelPayment(tossCancelCommand,
                idempotencyKey);

        // then
        assertThat(result.getPaymentKey()).isEqualTo(tossCancelCommand.getPaymentKey());
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.SUCCESS);
        assertThat(result.getPaymentFailure()).isNull();
    }
}
