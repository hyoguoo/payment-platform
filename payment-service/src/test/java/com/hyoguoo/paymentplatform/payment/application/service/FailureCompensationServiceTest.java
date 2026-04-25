package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockOutboxRepository;
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
 * <p>T-J1: FailureCompensationService가 stock_outbox INSERT + StockOutboxReadyEvent 발행으로 전환됨.
 * FakeStockOutboxRepository + CapturingApplicationEventPublisher로 검증.
 */
@DisplayName("FailureCompensationServiceTest")
class FailureCompensationServiceTest {

    private static final String ORDER_ID = "order-comp-001";
    private static final long PRODUCT_ID = 42L;
    private static final int QTY = 3;

    private CapturingApplicationEventPublisher eventPublisher;
    private FakeStockOutboxRepository stockOutboxRepository;
    private FailureCompensationService sut;

    @BeforeEach
    void setUp() {
        eventPublisher = new CapturingApplicationEventPublisher();
        stockOutboxRepository = new FakeStockOutboxRepository();
        // K5: LocalDateTimeProvider — 기존 테스트는 시간 값 검증 불필요 → default nowInstant() 위임
        LocalDateTimeProvider systemProvider = java.time.LocalDateTime::now;
        sut = new FailureCompensationService(eventPublisher, stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()), systemProvider);
    }

    // -----------------------------------------------------------------------
    // TC1: FAILED 전이 시 stock_outbox INSERT 1건 + StockOutboxReadyEvent 1건 발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("whenFailed_ShouldEnqueueStockRestoreCompensation — stock_outbox INSERT 1건 + StockOutboxReadyEvent 발행")
    void whenFailed_ShouldEnqueueStockRestoreCompensation() {
        // when
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        // then — stock_outbox 1건 저장
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(1);

        StockOutbox saved = stockOutboxRepository.allSaved().get(0);
        assertThat(saved.getTopic()).isEqualTo("stock.events.restore");
        assertThat(saved.getKey()).isEqualTo(String.valueOf(PRODUCT_ID));
        assertThat(saved.getPayload()).isNotNull();
        assertThat(saved.getPayload()).isNotBlank();
        assertThat(saved.getProcessedAt()).isNull();

        // then — StockOutboxReadyEvent 1건 발행
        List<StockOutboxReadyEvent> events = eventPublisher.capturedReadyEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).outboxId()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // TC2: 동일 orderId+productId 2회 → payload의 UUID 동일성 보장 (결정론적 UUID)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("whenFailed_IdempotentWhenCalledTwice — 동일 orderId+productId 2회 호출 시 payload eventUUID가 동일 (ADR-16 결정론적)")
    void whenFailed_IdempotentWhenCalledTwice() throws Exception {
        // when — 동일 orderId+productId로 2회 호출
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        // then — 2건 INSERT (dedupe는 consumer 측 책임)
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(2);

        // then — payload의 eventUUID 결정론적 동일성 검증
        ObjectMapper om = new ObjectMapper();
        List<StockOutbox> saved = stockOutboxRepository.allSaved();

        com.fasterxml.jackson.databind.JsonNode node0 = om.readTree(saved.stream()
                .sorted(java.util.Comparator.comparingLong(StockOutbox::getId))
                .toList().get(0).getPayload());
        com.fasterxml.jackson.databind.JsonNode node1 = om.readTree(saved.stream()
                .sorted(java.util.Comparator.comparingLong(StockOutbox::getId))
                .toList().get(1).getPayload());

        String uuid0 = node0.get("eventUUID").asText();
        String uuid1 = node1.get("eventUUID").asText();
        assertThat(uuid0).isEqualTo(uuid1);

        // 독립 인스턴스에서도 동일 UUID 생성
        CapturingApplicationEventPublisher anotherPublisher = new CapturingApplicationEventPublisher();
        FakeStockOutboxRepository anotherRepo = new FakeStockOutboxRepository();
        LocalDateTimeProvider anotherProvider = java.time.LocalDateTime::now;
        FailureCompensationService another = new FailureCompensationService(anotherPublisher, anotherRepo,
                new ObjectMapper().registerModule(new JavaTimeModule()), anotherProvider);
        another.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        StockOutbox anotherSaved = anotherRepo.allSaved().get(0);
        com.fasterxml.jackson.databind.JsonNode nodeAnother = om.readTree(anotherSaved.getPayload());
        assertThat(uuid0).isEqualTo(nodeAnother.get("eventUUID").asText());
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

        public List<StockOutboxReadyEvent> capturedReadyEvents() {
            return events.stream()
                    .filter(e -> e instanceof StockOutboxReadyEvent)
                    .map(e -> (StockOutboxReadyEvent) e)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }
}
