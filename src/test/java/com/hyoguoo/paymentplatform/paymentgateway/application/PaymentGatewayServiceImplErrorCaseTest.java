package com.hyoguoo.paymentplatform.paymentgateway.application;

import com.hyoguoo.paymentplatform.core.common.infrastructure.SystemUUIDProvider;
import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.core.common.util.EncodeUtils;
import com.hyoguoo.paymentplatform.mock.AdditionalHeaderHttpOperator;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.port.PaymentGatewayService;
import java.math.BigDecimal;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class PaymentGatewayServiceImplErrorCaseTest {

    @Autowired
    private PaymentGatewayService paymentGatewayService;

    @Autowired
    private HttpOperator httpOperator;

    @ParameterizedTest(name = "{index}: Test with TossPaymentErrorCode={0}")
    @EnumSource(TossPaymentErrorCode.class)
    @DisplayName("TossPaymentErrorCode에 따라 Header를 설정하고 결제 확인 결과를 검증한다.")
    void confirmPayment_withTossPaymentErrorCode(TossPaymentErrorCode errorCode) {
        // given
        String uuid = new SystemUUIDProvider().generateUUID();

        ReflectionTestUtils.invokeMethod(httpOperator, "addHeader", "TossPayments-Test-Code", errorCode.name());

        TossConfirmCommand tossConfirmCommand = TossConfirmCommand.builder()
                .orderId(uuid)
                .amount(BigDecimal.valueOf(10000))
                .paymentKey("validPaymentKey")
                .build();

        // when
        TossPaymentInfo tossPaymentInfo = paymentGatewayService.confirmPayment(tossConfirmCommand, uuid);
        PaymentConfirmResultStatus paymentConfirmResultStatus = tossPaymentInfo.getPaymentConfirmResultStatus();

        // then
        Assertions.assertThat(paymentConfirmResultStatus)
                .isEqualTo(getExpectedResultStatus(errorCode));
    }

    private PaymentConfirmResultStatus getExpectedResultStatus(TossPaymentErrorCode errorCode) {
        if (errorCode.isRetryableError()) {
            return PaymentConfirmResultStatus.RETRYABLE_FAILURE;
        } else if (errorCode.isFailure()) {
            return PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE;
        } else {
            return PaymentConfirmResultStatus.SUCCESS;
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public HttpOperator httpOperator() {
            return new AdditionalHeaderHttpOperator();
        }

        @Bean
        public EncodeUtils encodeUtils() {
            return new EncodeUtils();
        }
    }
}
