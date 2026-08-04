package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxCreationResult;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("PaymentOutboxRepositoryImpl — 발행 행 생성 세 갈래 판정")
class PaymentOutboxRepositoryImplTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-15T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String ORDER_ID = "order-outbox-creation-001";

    private JpaPaymentOutboxRepository jpaPaymentOutboxRepository;
    private PaymentOutboxRepositoryImpl sut;

    @BeforeEach
    void setUp() {
        jpaPaymentOutboxRepository = Mockito.mock(JpaPaymentOutboxRepository.class);
        sut = new PaymentOutboxRepositoryImpl(jpaPaymentOutboxRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("반영_행_1이면_생성됨을_반환한다")
    void 반영_행_1이면_생성됨을_반환한다() {
        // given
        given(jpaPaymentOutboxRepository.insertIgnorePending(eq(ORDER_ID), any())).willReturn(1);

        // when
        PaymentOutboxCreationResult result = sut.createPendingIfAbsent(ORDER_ID);

        // then
        assertThat(result).isEqualTo(PaymentOutboxCreationResult.CREATED);
        Mockito.verify(jpaPaymentOutboxRepository, Mockito.never()).findByOrderIdForUpdate(any());
    }

    @Test
    @DisplayName("반영_행_0이고_기존_행_존재면_이미_있음을_반환한다")
    void 반영_행_0이고_기존_행_존재면_이미_있음을_반환한다() {
        // given
        given(jpaPaymentOutboxRepository.insertIgnorePending(eq(ORDER_ID), any())).willReturn(0);
        given(jpaPaymentOutboxRepository.findByOrderIdForUpdate(ORDER_ID))
                .willReturn(Optional.of(Mockito.mock(PaymentOutboxEntity.class)));

        // when
        PaymentOutboxCreationResult result = sut.createPendingIfAbsent(ORDER_ID);

        // then
        assertThat(result).isEqualTo(PaymentOutboxCreationResult.ALREADY_EXISTS);
    }

    @Test
    @DisplayName("반영_행_0이고_기존_행_없으면_저장_실패를_반환한다")
    void 반영_행_0이고_기존_행_없으면_저장_실패를_반환한다() {
        // given
        given(jpaPaymentOutboxRepository.insertIgnorePending(eq(ORDER_ID), any())).willReturn(0);
        given(jpaPaymentOutboxRepository.findByOrderIdForUpdate(ORDER_ID)).willReturn(Optional.empty());

        // when
        PaymentOutboxCreationResult result = sut.createPendingIfAbsent(ORDER_ID);

        // then
        assertThat(result).isEqualTo(PaymentOutboxCreationResult.SAVE_FAILED);
    }
}
