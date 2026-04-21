package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * FAILED 결제 보상 서비스.
 * ADR-04(Transactional Outbox), ADR-16(UUID dedupe).
 * stock.events.restore 보상 이벤트를 outbox에 발행한다.
 *
 * <p>T3-04b 구현 예정 — 현재 스텁(RED 단계).
 */
@Service
@RequiredArgsConstructor
public class FailureCompensationService {

    private final StockRestoreEventPublisherPort stockRestoreEventPublisherPort;

    /**
     * FAILED 결제에 대한 재고 복원 보상 이벤트를 발행한다.
     * ADR-16: eventUUID는 orderId 기반 결정론적 생성 → 동일 orderId 재호출 시 동일 UUID.
     *
     * @param orderId    주문 ID
     * @param productIds 복원 대상 상품 ID 목록
     * @param qty        복원 수량
     */
    public void compensate(String orderId, List<Long> productIds, int qty) {
        // TODO T3-04b: 구현 필요
        throw new UnsupportedOperationException("T3-04b: 구현 필요");
    }
}
