package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.TossPaymentGatewayStrategy;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.NicepayPaymentGatewayStrategy;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Lazy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DuplicateApprovalHandler 순환 의존 해소 계약 테스트.
 *
 * <p>T3.5-05: PgGatewayPort 분해(PgStatusLookupPort + PgConfirmPort)로
 * NicepayPaymentGatewayStrategy ↔ DuplicateApprovalHandler ↔ PgGatewayPort self-loop를 근본 해소.
 *
 * <p>K13: TossPaymentGatewayStrategy ↔ DuplicateApprovalHandler cycle 근본 해소 (ApplicationEvent 패턴).
 * Toss/NicePay 전략 모두 DuplicateApprovalHandler 직접 의존 제거.
 *
 * <p>K14: DuplicateApprovalHandler 가 PgStatusLookupPort 단일 의존 대신
 * PgStatusLookupStrategySelector 를 통한 vendorType 기반 분기로 확장.
 *
 * <p>불변식:
 * <ul>
 *   <li>DuplicateApprovalHandler 생성자 파라미터 중 {@code @Lazy} 어노테이션 부재</li>
 *   <li>DuplicateApprovalHandler 필드 중 {@link PgStatusLookupStrategySelector} 타입 존재 (K14)</li>
 *   <li>DuplicateApprovalHandler 필드 중 {@code PgGatewayPort} 타입 부재</li>
 *   <li>DuplicateApprovalHandler 생성자 첫 파라미터가 {@link PgStatusLookupStrategySelector} 타입 (K14)</li>
 *   <li>TossPaymentGatewayStrategy 필드 중 {@code DuplicateApprovalHandler} 타입 부재 (K13)</li>
 *   <li>NicepayPaymentGatewayStrategy 필드 중 {@code DuplicateApprovalHandler} 타입 부재 (K13)</li>
 * </ul>
 */
@DisplayName("DuplicateApprovalHandler 순환 의존 해소 계약")
class DuplicateApprovalHandlerCircularDependencyTest {

    /**
     * TC1: DuplicateApprovalHandler 생성자 파라미터에 @Lazy 어노테이션이 없어야 한다.
     *
     * <p>@Lazy는 순환 의존 임시 우회책으로 사용되었으나, T3.5-05에서 포트 분해로 완전 해소.
     * 이 테스트가 PASS = @Lazy가 완전히 제거된 상태.
     */
    @Test
    @DisplayName("생성자_파라미터에_@Lazy_어노테이션이_없어야_한다")
    void 생성자_파라미터에_Lazy_어노테이션이_없어야_한다() {
        boolean hasLazyAnnotation = Arrays.stream(DuplicateApprovalHandler.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterAnnotations()))
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation.annotationType() == Lazy.class);

        assertThat(hasLazyAnnotation)
                .as("DuplicateApprovalHandler 생성자에 @Lazy 어노테이션이 존재하면 안 됨 — T3.5-05 포트 분해로 순환 해소")
                .isFalse();
    }

    /**
     * TC2: DuplicateApprovalHandler 필드 중 PgStatusLookupStrategySelector 타입이 존재해야 한다.
     *
     * <p>K14: PgStatusLookupPort 단일 의존 → PgStatusLookupStrategySelector 로 교체.
     * vendorType 기반 분기로 Toss/NicePay 동시 활성 지원.
     */
    @Test
    @DisplayName("PgStatusLookupStrategySelector_필드가_존재해야_한다")
    void PgStatusLookupStrategySelector_필드가_존재해야_한다() {
        boolean hasSelectorField = Arrays.stream(DuplicateApprovalHandler.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == PgStatusLookupStrategySelector.class);

        assertThat(hasSelectorField)
                .as("DuplicateApprovalHandler에 PgStatusLookupStrategySelector 타입 필드가 있어야 함 — K14 selector 분기 불변식")
                .isTrue();
    }

    /**
     * TC3: DuplicateApprovalHandler 필드 중 PgGatewayPort 타입이 없어야 한다.
     *
     * <p>T3.5-05 포트 완전 교체 — PgGatewayPort 잔재 0건.
     */
    @Test
    @DisplayName("PgGatewayPort_필드가_없어야_한다")
    void PgGatewayPort_필드가_없어야_한다() {
        boolean hasPgGatewayPortField = Arrays.stream(DuplicateApprovalHandler.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(type -> type.getSimpleName().equals("PgGatewayPort"));

        assertThat(hasPgGatewayPortField)
                .as("DuplicateApprovalHandler에 PgGatewayPort 타입 필드가 없어야 함 — T3.5-05 포트 분해로 완전 제거")
                .isFalse();
    }

    /**
     * TC4: DuplicateApprovalHandler 생성자의 첫 파라미터가 PgStatusLookupStrategySelector 타입이어야 한다.
     *
     * <p>K14: 생성자 시그니처 갱신 — (PgStatusLookupStrategySelector, PgInboxRepository, ...).
     */
    @Test
    @DisplayName("생성자_첫_파라미터가_PgStatusLookupStrategySelector_이어야_한다")
    void 생성자_첫_파라미터가_PgStatusLookupStrategySelector_이어야_한다() {
        boolean firstParamIsSelector = Arrays.stream(DuplicateApprovalHandler.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() > 0)
                .anyMatch(c -> c.getParameterTypes()[0] == PgStatusLookupStrategySelector.class);

        assertThat(firstParamIsSelector)
                .as("DuplicateApprovalHandler 생성자 첫 파라미터가 PgStatusLookupStrategySelector 이어야 함 — K14 selector 불변식")
                .isTrue();
    }

    /**
     * TC5: TossPaymentGatewayStrategy 필드 중 DuplicateApprovalHandler 타입이 없어야 한다.
     *
     * <p>K13: Toss 전략이 DuplicateApprovalHandler를 직접 보유하면 PgStatusLookupPort 구현체(Toss) ↔
     * DuplicateApprovalHandler ↔ PgStatusLookupPort cycle 이 형성됨.
     * ApplicationEvent 패턴으로 cycle 영구 단절.
     */
    @Test
    @DisplayName("Toss전략_DuplicateApprovalHandler_직접_의존_없어야_한다")
    void Toss전략_DuplicateApprovalHandler_직접_의존_없어야_한다() {
        boolean hasDuplicateApprovalHandlerField = Arrays.stream(TossPaymentGatewayStrategy.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == DuplicateApprovalHandler.class);

        assertThat(hasDuplicateApprovalHandlerField)
                .as("TossPaymentGatewayStrategy에 DuplicateApprovalHandler 필드가 있으면 안 됨 — K13 cycle 단절")
                .isFalse();
    }

    /**
     * TC6: NicepayPaymentGatewayStrategy 필드 중 DuplicateApprovalHandler 타입이 없어야 한다.
     *
     * <p>K13: NicePay 전략도 동일한 cycle 위험 — DuplicateApprovalHandler 직접 의존 제거.
     */
    @Test
    @DisplayName("NicePay전략_DuplicateApprovalHandler_직접_의존_없어야_한다")
    void NicePay전략_DuplicateApprovalHandler_직접_의존_없어야_한다() {
        boolean hasDuplicateApprovalHandlerField = Arrays.stream(NicepayPaymentGatewayStrategy.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == DuplicateApprovalHandler.class);

        assertThat(hasDuplicateApprovalHandlerField)
                .as("NicepayPaymentGatewayStrategy에 DuplicateApprovalHandler 필드가 있으면 안 됨 — K13 cycle 단절")
                .isFalse();
    }
}
