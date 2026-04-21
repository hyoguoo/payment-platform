package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import java.util.Optional;

/**
 * pg-service outbound 포트 — business inbox 저장소 계약.
 * ADR-21: order_id UNIQUE 보장, 5상태 전이.
 * 구현체(JPA 어댑터)는 T2a-04에서 추가.
 */
public interface PgInboxRepository {

    Optional<PgInbox> findByOrderId(String orderId);

    PgInbox save(PgInbox inbox);
}
