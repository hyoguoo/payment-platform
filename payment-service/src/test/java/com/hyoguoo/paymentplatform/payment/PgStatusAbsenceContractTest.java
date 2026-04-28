package com.hyoguoo.paymentplatform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계약 테스트 — payment-service 내에 PG 직접 HTTP 호출 잔재가 없음을 정적으로 고정한다.
 * payment ↔ pg 간 상태 조회는 Kafka 만 사용하며, PG 호출은 pg-service 가 전담한다.
 * payment-service 내에 PgStatusPort / PgStatusHttpAdapter 가 존재해서는 안 된다.
 */
@DisplayName("PgStatus 부재 계약 테스트 (불변식 19)")
class PgStatusAbsenceContractTest {

    /**
     * TC1: payment-service 에 PgStatusPort 인터페이스가 존재하지 않음을 검증한다.
     * 해당 클래스가 존재하면 payment ↔ pg 간 직접 호출 금지 정책 위반이다.
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
     * getStatus / getStatusByOrderId 메서드가 PaymentCommandUseCase 에 없어야 하며,
     * payment-service 안에 PaymentGatewayStrategy / PaymentGatewayPort 자체가 잔존해서도 안 된다.
     */
    @Test
    @DisplayName("TC3: PaymentCommandUseCase에 getStatus 계열 메서드가 없고 PaymentGatewayStrategy·PaymentGatewayPort 클래스도 제거되어야 한다")
    void executePaymentAndOutbox_ShouldNotWrapPgCall() {
        List<String> prohibitedMethods = List.of("getStatus", "getStatusByOrderId", "getPaymentStatusByOrderId");

        // PaymentCommandUseCase에 PG 상태 조회 메서드가 없어야 한다
        List<String> useCaseMethodNames = Arrays.stream(PaymentCommandUseCase.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        for (String prohibited : prohibitedMethods) {
            assertThat(useCaseMethodNames)
                    .as("PaymentCommandUseCase 에 %s 메서드가 없어야 한다 (payment→pg 직접 호출 금지)", prohibited)
                    .doesNotContain(prohibited);
        }

        // PaymentGatewayStrategy 계층 자체가 payment-service에 존재하지 않아야 한다
        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy")
        ).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayFactory")
        ).isInstanceOf(ClassNotFoundException.class);

        // 구버전 PaymentGatewayPort (getStatus 포함 버전)가 application.port 패키지에 없어야 한다
        assertThatThrownBy(() ->
                Class.forName("com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort")
        ).isInstanceOf(ClassNotFoundException.class);
    }
}
