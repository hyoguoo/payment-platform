package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockRestoreEventPublisher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FailureCompensationService 단위 테스트.
 * ADR-04(Transactional Outbox), ADR-16(UUID dedupe).
 * domain_risk=true: FAILED 전이 보상 이벤트 발행 + UUID 멱등성 불변 커버.
 */
@DisplayName("FailureCompensationServiceTest")
class FailureCompensationServiceTest {

    private static final String ORDER_ID = "order-comp-001";
    private static final long PRODUCT_ID = 42L;
    private static final int QTY = 3;

    private FakeStockRestoreEventPublisher stockRestorePublisher;
    private FailureCompensationService sut;

    @BeforeEach
    void setUp() {
        stockRestorePublisher = new FakeStockRestoreEventPublisher();
        sut = new FailureCompensationService(stockRestorePublisher);
    }

    // -----------------------------------------------------------------------
    // TC1: FAILED 전이 시 payment_outbox에 stock.events.restore row 1건 INSERT
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("whenFailed_ShouldEnqueueStockRestoreCompensation — FAILED 전이 시 stock.events.restore row 1건 INSERT(orderId·productId·qty·eventUUID 필드 포함)")
    void whenFailed_ShouldEnqueueStockRestoreCompensation() {
        // when
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        // then — 1건만 발행
        assertThat(stockRestorePublisher.publishedPayloads()).hasSize(1);

        StockRestoreEventPayload payload = stockRestorePublisher.publishedPayloads().get(0);
        assertThat(payload.orderId()).isEqualTo(ORDER_ID);
        assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
        assertThat(payload.qty()).isEqualTo(QTY);
        assertThat(payload.eventUUID()).isNotNull();
        assertThat(payload.eventUUID().toString()).isNotBlank();
    }

    // -----------------------------------------------------------------------
    // TC2: 동일 orderId 2회 → outbox row 1건만 INSERT (멱등 UUID 보장)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("whenFailed_IdempotentWhenCalledTwice — 동일 orderId 2회 → outbox row 1건만 INSERT(멱등 UUID 보장)")
    void whenFailed_IdempotentWhenCalledTwice() {
        // when — 동일 orderId로 2회 호출
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);
        sut.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);

        // then — 동일 UUID → 두 번째 INSERT no-op → 총 1건
        assertThat(stockRestorePublisher.publishedPayloads()).hasSize(1);

        // UUID 동일성 검증
        StockRestoreEventPayload payload = stockRestorePublisher.publishedPayloads().get(0);
        assertThat(payload.eventUUID()).isNotNull();

        // 두 번 독립 호출 후 UUID 값이 동일한지 확인 (결정론적 생성)
        FakeStockRestoreEventPublisher anotherPublisher = new FakeStockRestoreEventPublisher();
        FailureCompensationService another = new FailureCompensationService(anotherPublisher);
        another.compensate(ORDER_ID, List.of(PRODUCT_ID), QTY);
        StockRestoreEventPayload anotherPayload = anotherPublisher.publishedPayloads().get(0);
        assertThat(payload.eventUUID()).isEqualTo(anotherPayload.eventUUID());
    }
}
