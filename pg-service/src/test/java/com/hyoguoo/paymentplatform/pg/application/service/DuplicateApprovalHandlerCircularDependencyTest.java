package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
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
 * <p>불변식:
 * <ul>
 *   <li>DuplicateApprovalHandler 생성자 파라미터 중 {@code @Lazy} 어노테이션 부재</li>
 *   <li>DuplicateApprovalHandler 필드 중 {@link PgStatusLookupPort} 타입 존재</li>
 *   <li>DuplicateApprovalHandler 필드 중 {@code PgGatewayPort} 타입 부재</li>
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
     * TC2: DuplicateApprovalHandler 필드 중 PgStatusLookupPort 타입이 존재해야 한다.
     *
     * <p>포트 분해 후 DuplicateApprovalHandler는 PgStatusLookupPort만 의존.
     */
    @Test
    @DisplayName("PgStatusLookupPort_필드가_존재해야_한다")
    void PgStatusLookupPort_필드가_존재해야_한다() {
        boolean hasPgStatusLookupPortField = Arrays.stream(DuplicateApprovalHandler.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == PgStatusLookupPort.class);

        assertThat(hasPgStatusLookupPortField)
                .as("DuplicateApprovalHandler에 PgStatusLookupPort 타입 필드가 있어야 함 — T3.5-05 포트 분해 불변식")
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
     * TC4: DuplicateApprovalHandler 생성자의 첫 파라미터가 PgStatusLookupPort 타입이어야 한다.
     *
     * <p>생성자 시그니처: (PgStatusLookupPort, PgInboxRepository, PgOutboxRepository,
     * ApplicationEventPublisher, ConfirmedEventPayloadSerializer)
     */
    @Test
    @DisplayName("생성자_첫_파라미터가_PgStatusLookupPort_이어야_한다")
    void 생성자_첫_파라미터가_PgStatusLookupPort_이어야_한다() {
        boolean firstParamIsPgStatusLookupPort = Arrays.stream(DuplicateApprovalHandler.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() > 0)
                .anyMatch(c -> c.getParameterTypes()[0] == PgStatusLookupPort.class);

        assertThat(firstParamIsPgStatusLookupPort)
                .as("DuplicateApprovalHandler 생성자 첫 파라미터가 PgStatusLookupPort 이어야 함")
                .isTrue();
    }
}
