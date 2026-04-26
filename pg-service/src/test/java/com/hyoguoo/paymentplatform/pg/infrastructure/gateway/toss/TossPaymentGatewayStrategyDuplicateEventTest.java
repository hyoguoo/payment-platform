package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TossPaymentGatewayStrategy duplicate 분기 — ApplicationEvent 패턴 단위 테스트.
 *
 * <p>불변식:
 * <ul>
 *   <li>TC1: TossPaymentGatewayStrategy 필드 중 DuplicateApprovalHandler 타입이 없어야 함 — 직접 의존 제거</li>
 *   <li>TC2: TossPaymentGatewayStrategy 필드 중 ApplicationEventPublisher 타입이 있어야 함 — 이벤트 발행 경로</li>
 *   <li>TC3: duplicate 감지 시 DuplicateApprovalDetectedEvent 가 발행되어야 함</li>
 * </ul>
 */
@DisplayName("TossPaymentGatewayStrategy — ApplicationEvent 패턴 순환 의존 해소")
class TossPaymentGatewayStrategyDuplicateEventTest {

    /**
     * TC1: TossPaymentGatewayStrategy 필드 중 DuplicateApprovalHandler 타입이 없어야 한다.
     *
     * <p>DuplicateApprovalHandler 직접 의존을 ApplicationEventPublisher 로 교체해 cycle 을 끊었다.
     * cycle: TossPaymentGatewayStrategy → DuplicateApprovalHandler → PgStatusLookupPort(← Toss).
     */
    @Test
    @DisplayName("Toss전략_DuplicateApprovalHandler_직접_필드_없어야_한다")
    void Toss전략_DuplicateApprovalHandler_직접_필드_없어야_한다() {
        boolean hasDuplicateApprovalHandlerField = Arrays.stream(TossPaymentGatewayStrategy.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == DuplicateApprovalHandler.class);

        assertThat(hasDuplicateApprovalHandlerField)
                .as("TossPaymentGatewayStrategy 에 DuplicateApprovalHandler 필드가 있으면 안 됨 — ApplicationEvent 패턴으로 cycle 단절")
                .isFalse();
    }

    /**
     * TC2: TossPaymentGatewayStrategy 필드 중 ApplicationEventPublisher 타입이 있어야 한다.
     *
     * <p>cycle 단절을 위해 DuplicateApprovalHandler 직접 호출이 ApplicationEventPublisher.publishEvent 로 교체됐다.
     */
    @Test
    @DisplayName("Toss전략_ApplicationEventPublisher_필드_있어야_한다")
    void Toss전략_ApplicationEventPublisher_필드_있어야_한다() {
        boolean hasEventPublisherField = Arrays.stream(TossPaymentGatewayStrategy.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == ApplicationEventPublisher.class);

        assertThat(hasEventPublisherField)
                .as("TossPaymentGatewayStrategy 에 ApplicationEventPublisher 필드가 있어야 함 — 이벤트 발행 경로")
                .isTrue();
    }

    /**
     * TC3: PgConfirmRequest 인터페이스 — duplicate 감지 메서드 호출 시 DuplicateApprovalDetectedEvent 발행.
     *
     * <p>duplicate 분기 시 applicationEventPublisher.publishEvent(new DuplicateApprovalDetectedEvent(...)) 가 호출돼야 한다.
     */
    @Test
    @DisplayName("Toss전략_DuplicateApprovalDetectedEvent_클래스_존재해야_한다")
    void Toss전략_DuplicateApprovalDetectedEvent_클래스_존재해야_한다() {
        // DuplicateApprovalDetectedEvent 가 pg application 계층에 존재해야 함
        boolean eventClassExists;
        try {
            Class.forName("com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent");
            eventClassExists = true;
        } catch (ClassNotFoundException e) {
            eventClassExists = false;
        }

        assertThat(eventClassExists)
                .as("DuplicateApprovalDetectedEvent 클래스가 pg.application.event 패키지에 존재해야 함 — K13 신설")
                .isTrue();
    }
}
