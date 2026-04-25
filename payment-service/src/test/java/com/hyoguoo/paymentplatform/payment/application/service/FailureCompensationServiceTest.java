package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * FailureCompensationService 단위 테스트.
 * ADR-04(Transactional Outbox), ADR-16(UUID dedupe).
 * domain_risk=true: FAILED 전이 보상 이벤트 발행 + UUID 멱등성 불변 커버.
 *
 * <p>T-I4: FailureCompensationService가 ApplicationEventPublisher 경유로 StockRestoreRequestedEvent
 * (ContextSnapshot 포함)를 발행하도록 변경됨에 따라 검증 로직 갱신.
 */
@DisplayName("FailureCompensationServiceTest")
class FailureCompensationServiceTest {

    private static final String ORDER_ID = "order-comp-001";
    private static final long PRODUCT_ID = 42L;
    private static final int QTY = 3;

    private CapturingApplicationEventPublisher eventPublisher;
    private FailureCompensationService sut;

    @BeforeEach
    void setUp() {
        eventPublisher = new CapturingApplicationEventPublisher();
        sut = new FailureCompensationService(eventPublisher, ObservationRegistry.NOOP);
    }

    // -----------------------------------------------------------------------
    // TC1: FAILED 전이 시 StockRestoreRequestedEvent 1건 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("whenFailed_ShouldEnqueueStockRestoreCompensation — FAILED 전이 시 StockRestoreRequestedEvent 1건 발행(orderId·productId·qty·eventUUID 필드 포함)")
    void whenFailed_ShouldEnqueueStockRestoreCompensation() {
        // when
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        // then — 1건만 발행
        List<StockRestoreRequestedEvent> events = eventPublisher.capturedRestoreEvents();
        assertThat(events).hasSize(1);

        StockRestoreRequestedEvent event = events.get(0);
        assertThat(event.orderId()).isEqualTo(ORDER_ID);
        assertThat(event.productId()).isEqualTo(PRODUCT_ID);
        assertThat(event.quantity()).isEqualTo(QTY);
        assertThat(event.eventUUID()).isNotNull();
        assertThat(event.eventUUID()).isNotBlank();
        // T-I4: contextSnapshot이 non-null이어야 한다
        assertThat(event.contextSnapshot()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // TC2: 동일 orderId+productId 2회 → UUID 동일성 보장 (결정론적 UUID)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("whenFailed_IdempotentWhenCalledTwice — 동일 orderId+productId 2회 호출 시 발행 UUID가 동일하다 (ADR-16 결정론적)")
    void whenFailed_IdempotentWhenCalledTwice() {
        // when — 동일 orderId+productId로 2회 호출
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        // then — 2번 모두 발행되지만(Fake는 멱등 시뮬레이션 없음, 실제 dedupe는 consumer 측 책임)
        // UUID 결정론적 검증 — 동일 입력이면 동일 UUID
        List<StockRestoreRequestedEvent> events = eventPublisher.capturedRestoreEvents();
        assertThat(events).hasSize(2);

        String uuid1 = events.get(0).eventUUID();
        String uuid2 = events.get(1).eventUUID();
        assertThat(uuid1).isEqualTo(uuid2);

        // 독립 인스턴스에서도 동일 UUID 생성
        CapturingApplicationEventPublisher anotherPublisher = new CapturingApplicationEventPublisher();
        FailureCompensationService another = new FailureCompensationService(anotherPublisher, ObservationRegistry.NOOP);
        another.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        String uuid3 = anotherPublisher.capturedRestoreEvents().get(0).eventUUID();
        assertThat(uuid1).isEqualTo(uuid3);
    }

    // ---- helper: ApplicationEventPublisher that captures events ----

    static class CapturingApplicationEventPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        public List<StockRestoreRequestedEvent> capturedRestoreEvents() {
            return events.stream()
                    .filter(e -> e instanceof StockRestoreRequestedEvent)
                    .map(e -> (StockRestoreRequestedEvent) e)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }
}
