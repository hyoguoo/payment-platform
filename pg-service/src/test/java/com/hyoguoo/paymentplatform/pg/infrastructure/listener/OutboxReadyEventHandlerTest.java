package com.hyoguoo.paymentplatform.pg.infrastructure.listener;

import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxReadyEventHandler 단위 스모크 테스트.
 * domain_risk=true: handle() 호출 시 channel.offerNow(outboxId) 가 반드시 호출되는지 검증.
 * AFTER_COMMIT 실제 통합 테스트는 T2a-06 이후에 Spring context 통합 테스트로 묶어 진행.
 *
 * <p>T-J4: offer → offerNow 호출 변경 반영.
 */
@DisplayName("OutboxReadyEventHandler")
@ExtendWith(MockitoExtension.class)
class OutboxReadyEventHandlerTest {

    @Mock
    private PgOutboxChannel channel;

    private OutboxReadyEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OutboxReadyEventHandler(channel);
    }

    @Test
    @DisplayName("handle — channel.offerNow(outboxId) 가 호출된다")
    void handle_offerNow가_호출된다() {
        when(channel.offerNow(100L)).thenReturn(true);

        handler.handle(new PgOutboxReadyEvent(100L));

        verify(channel).offerNow(100L);
    }

    @Test
    @DisplayName("handle — offerNow 가 false 반환 시 warn 로그만 기록하고 예외를 던지지 않는다 (오버플로우 안전망)")
    void handle_offerNow_false_시_예외없음() {
        when(channel.offerNow(200L)).thenReturn(false);

        // warn 로그만 기록하고 예외 없이 정상 종료 — Polling Worker fallback 보장
        handler.handle(new PgOutboxReadyEvent(200L));

        verify(channel).offerNow(200L);
    }

    @Test
    @DisplayName("handle — FakeChannel 로 실제 offerNow 동작 검증 (Mockito 없이)")
    void handle_FakeChannel로_offerNow_검증() {
        PgOutboxChannel fakeChannel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        fakeChannel.registerMetrics();
        OutboxReadyEventHandler fakeHandler = new OutboxReadyEventHandler(fakeChannel);

        fakeHandler.handle(new PgOutboxReadyEvent(999L));

        // 채널에 항목이 1개 쌓였는지 확인
        org.assertj.core.api.Assertions.assertThat(fakeChannel.size()).isEqualTo(1);
    }
}
