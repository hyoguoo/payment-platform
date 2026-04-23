package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.presentation.port.StockRestoreCommandService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 복원 유스케이스.
 * <p>
 * ADR-16: 보상 dedupe 소유 = consumer 측.
 * <p>
 * 불변식:
 * <ul>
 *   <li>불변식 14: 동일 eventUUID dedupe — existsValid true 반환 시 즉시 return (no-op)</li>
 *   <li>재고 증가 성공 후 dedupe 기록 — 재고 실패 시 dedupe 미기록</li>
 *   <li>TTL = Kafka retention(7일) + 1일 = 8일</li>
 * </ul>
 * <p>
 * 처리 순서:
 * 1. dedupe check (existsValid): 유효 중복이면 no-op
 * 2. 재고 조회 + qty 증가 + save (실패 시 IllegalStateException)
 * 3. dedupe 기록 (recordIfAbsent) — 재고 증가 성공 후만
 * <p>
 * @Transactional: JdbcEventDedupeStore.recordIfAbsent (DB INSERT) + StockRepository.save 원자성.
 * 재고 증가 실패 시 dedupe INSERT도 롤백 (JdbcEventDedupeStore 환경).
 * FakeEventDedupeStore(in-memory) 환경에서는 트랜잭션 롤백이 없으므로,
 * 재고 조회 실패(productId 미존재) 케이스에서 dedupe 기록 전 예외 발생 → dedupe 미기록 검증 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRestoreUseCase implements StockRestoreCommandService {

    /**
     * TTL = Kafka 기본 retention(7일) + 1일 = 8일.
     */
    static final Duration DEDUPE_TTL = Duration.ofDays(8);

    private final StockRepository stockRepository;
    private final EventDedupeStore eventDedupeStore;

    /**
     * 지정 상품의 재고를 qty만큼 복원한다.
     *
     * @param orderId   주문 식별자
     * @param eventUUID 이벤트 UUID (dedupe 키)
     * @param productId 복원 대상 상품 ID
     * @param qty       복원 수량
     * @throws IllegalStateException 해당 상품 재고가 존재하지 않을 경우
     */
    @Override
    @Transactional
    public void restore(String orderId, String eventUUID, long productId, int qty) {
        if (eventDedupeStore.existsValid(eventUUID)) {
            log.info("StockRestoreUseCase: 중복 이벤트 무시 eventUUID={} orderId={} productId={}",
                    eventUUID, orderId, productId);
            return;
        }

        restoreStockInRdb(productId, qty, orderId, eventUUID);

        eventDedupeStore.recordIfAbsent(eventUUID, Instant.now().plus(DEDUPE_TTL));

        log.info("StockRestoreUseCase: 재고 복원 완료 orderId={} productId={} qty={} eventUUID={}",
                orderId, productId, qty, eventUUID);
    }

    private void restoreStockInRdb(long productId, int qty, String orderId, String eventUUID) {
        Stock current = stockRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalStateException(
                        "재고 정보를 찾을 수 없음 productId=" + productId
                                + " orderId=" + orderId
                                + " eventUUID=" + eventUUID));

        int newQuantity = current.getQuantity() + qty;
        Stock updated = Stock.allArgsBuilder()
                .productId(productId)
                .quantity(newQuantity)
                .allArgsBuild();
        stockRepository.save(updated);

        log.info("StockRestoreUseCase: RDB UPDATE 완료 productId={} {} -> {}",
                productId, current.getQuantity(), newQuantity);
    }
}
