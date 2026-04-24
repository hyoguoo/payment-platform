package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * FAILED 결제 보상 서비스.
 * ADR-04(Transactional Outbox), ADR-16(UUID dedupe).
 * stock.events.restore 보상 이벤트를 outbox에 발행한다.
 *
 * <p>UUID 멱등성 전략: UUID v3(nameUUIDFromBytes) — "stock-restore:{orderId}" 기반 결정론적 생성.
 * 동일 orderId 재호출 시 동일 UUID가 생성되어 outbox 구현체의 UNIQUE 제약 또는 dedupe 로직과 결합,
 * 두 번째 INSERT를 no-op 처리한다. (ADR-16)
 */
@Service
@RequiredArgsConstructor
public class FailureCompensationService {

    private final StockRestoreEventPublisherPort stockRestoreEventPublisherPort;

    /**
     * FAILED 결제에 대한 재고 복원 보상 이벤트를 발행한다.
     * ADR-16: eventUUID는 orderId 기반 결정론적 생성 → 동일 orderId 재호출 시 동일 UUID.
     *
     * <p>productIds 리스트의 각 상품에 대해 개별 payload를 발행한다.
     * 단, 동일 orderId + productId 조합의 UUID 결정론적 생성으로 멱등성 보장.
     *
     * @param orderId    주문 ID
     * @param productIds 복원 대상 상품 ID 목록
     * @param qty        복원 수량 (각 상품별 동일 수량 적용)
     */
    public void compensate(String orderId, List<Long> productIds, int qty) {
        for (Long productId : productIds) {
            compensate(orderId, productId, qty);
        }
    }

    /**
     * 단일 상품에 대한 FAILED 결제 재고 복원 보상 이벤트를 발행한다.
     * ADR-16: eventUUID는 orderId+productId 기반 결정론적 생성 → 동일 조합 재호출 시 동일 UUID.
     *
     * <p>T-B1: handleFailed 루프 내부에서 실 qty와 함께 호출하는 단위 진입점.
     * 레거시 qty=0 플레이스홀더 경로(publish(orderId, productIds))를 대체한다.
     *
     * @param orderId   주문 ID
     * @param productId 복원 대상 상품 ID
     * @param qty       복원 수량 (실 주문 수량)
     */
    public void compensate(String orderId, Long productId, int qty) {
        UUID eventUUID = deriveEventUUID(orderId, productId);
        StockRestoreEventPayload payload = new StockRestoreEventPayload(
                eventUUID,
                orderId,
                productId,
                qty
        );
        stockRestoreEventPublisherPort.publishPayload(payload);
    }

    /**
     * orderId + productId 기반 결정론적 UUID 생성.
     * ADR-16: "stock-restore:{orderId}:{productId}" → UUID v3(name-based).
     * 동일 입력 → 동일 UUID 출력 보장.
     */
    private UUID deriveEventUUID(String orderId, Long productId) {
        String seed = "stock-restore:" + orderId + ":" + productId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}

