package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.presentation.port.StockRestoreCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재고 복원 유스케이스 스캐폴드.
 * T3-01: 구조만 선언. 실제 복원 로직(EventDedupeStore dedupe + StockRepository 조회/갱신)은
 * T3-05에서 완성 예정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRestoreUseCase implements StockRestoreCommandService {

    @Override
    public void restore(String orderId, String eventUuid) {
        // T3-05에서 구현 완성 예정
        // 1. EventDedupeStore.recordIfAbsent(eventUuid, TTL) — dedupe
        // 2. 주문 연관 Stock 조회 → incrementStock
        // 3. StockRepository.save
        throw new UnsupportedOperationException("T3-05에서 구현 예정");
    }
}
