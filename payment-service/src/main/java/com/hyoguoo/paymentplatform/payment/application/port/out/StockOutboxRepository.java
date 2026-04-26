package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * stock_outbox 저장소 포트 — stock commit/restore 이벤트의 transactional outbox 저장소.
 *
 * <p>pg-service {@code PgOutboxRepository} 와 동격 구조이지만 공유 JAR 없이 독립 복제한다.
 */
public interface StockOutboxRepository {

    /**
     * outbox row를 저장한다. 저장된 row의 id(AUTO_INCREMENT)를 포함한 도메인 객체를 반환한다.
     */
    StockOutbox save(StockOutbox stockOutbox);

    /**
     * id(PK)로 outbox row를 조회한다. 없으면 Optional.empty().
     */
    Optional<StockOutbox> findById(Long id);

    /**
     * 성공적으로 발행된 row의 processed_at을 갱신한다.
     */
    void markProcessed(Long id, LocalDateTime processedAt);
}
