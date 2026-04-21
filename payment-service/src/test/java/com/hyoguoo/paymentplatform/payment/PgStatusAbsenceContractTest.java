package com.hyoguoo.paymentplatform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 불변식 19 계약 테스트: payment-service 내 PG 직접 HTTP 호출 잔재가 없음을 정적으로 고정한다.
 * ADR-02: payment↔pg 간 상태 조회는 Kafka only.
 * ADR-21: PG 호출은 pg-service 책임. payment-service 내 PgStatusPort·PgStatusHttpAdapter 부재.
 */
@DisplayName("PgStatus 부재 계약 테스트 (불변식 19)")
class PgStatusAbsenceContractTest {

    /**
     * TC1: payment-service에 PgStatusPort 인터페이스가 존재하지 않음을 검증한다.
     * 해당 클래스가 존재하면 ADR-02/ADR-21 위반이다.
     */
    @Test
    @DisplayName("TC1: PgStatusPort가 payment-service 클래스패스에 없어야 한다")
    void pgStatusPort_ShouldNotExistInPaymentService() {
        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.application.port.PgStatusPort")
        ).isInstanceOf(ClassNotFoundException.class);

        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.application.port.out.PgStatusPort")
        ).isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * TC2: payment-service에 PgStatusHttpAdapter 클래스가 존재하지 않음을 검증한다.
     * 해당 클래스가 존재하면 pg-service 책임을 payment-service가 침범하는 것이다.
     */
    @Test
    @DisplayName("TC2: PgStatusHttpAdapter가 payment-service 클래스패스에 없어야 한다")
    void pgStatusHttpAdapter_ShouldNotExistInPaymentService() {
        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.infrastructure.PgStatusHttpAdapter")
        ).isInstanceOf(ClassNotFoundException.class);

        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.infrastructure.adapter.PgStatusHttpAdapter")
        ).isInstanceOf(ClassNotFoundException.class);

        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.infrastructure.internal.PgStatusHttpAdapter")
        ).isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * TC3: payment-service TX 내 PG HTTP 상태 조회 경로가 없음을 검증한다.
     * ADR-02: getStatus/getStatusByOrderId 메서드가 PaymentCommandUseCase 및 PaymentGatewayStrategy에 없어야 한다.
     * 불변식 12: payment-service TX 내 PG HTTP 호출 금지.
     */
    @Test
    @DisplayName("TC3: PaymentCommandUseCase와 PaymentGatewayStrategy에 getStatus/getStatusByOrderId 메서드가 없어야 한다")
    void executePaymentAndOutbox_ShouldNotWrapPgCall() {
        List<String> prohibitedMethods = List.of("getStatus", "getStatusByOrderId", "getPaymentStatusByOrderId");

        // PaymentCommandUseCase에 PG 상태 조회 메서드가 없어야 한다
        List<String> useCaseMethodNames = Arrays.stream(PaymentCommandUseCase.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        for (String prohibited : prohibitedMethods) {
            assertThat(useCaseMethodNames)
                    .as("PaymentCommandUseCase에 %s 메서드가 없어야 한다 (ADR-02)", prohibited)
                    .doesNotContain(prohibited);
        }

        // PaymentGatewayStrategy에 PG 상태 조회 메서드가 없어야 한다
        List<String> strategyMethodNames = Arrays.stream(PaymentGatewayStrategy.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        for (String prohibited : List.of("getStatus", "getStatusByOrderId")) {
            assertThat(strategyMethodNames)
                    .as("PaymentGatewayStrategy에 %s 메서드가 없어야 한다 (ADR-02)", prohibited)
                    .doesNotContain(prohibited);
        }

        // 구버전 PaymentGatewayPort (getStatus 포함 버전)가 application.port 패키지에 없어야 한다
        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort")
        ).isInstanceOf(ClassNotFoundException.class);
    }
}
