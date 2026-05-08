package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service listener TX 경계 봉인 서비스 (PCS-7).
 *
 * <p>§1.1 — listener 가 호출하는 단일 메서드 {@link #insertPendingAndPublish} 에 TX 경계를 봉인한다.
 * {@code @Transactional(propagation=REQUIRED, timeout=5)} 보장:
 * <ul>
 *   <li>pg_inbox PENDING INSERT + {@link ApplicationEventPublisher#publishEvent} 를 같은 TX 위에서 실행.</li>
 *   <li>TX 내부에서 publishEvent 를 호출해야 {@code @TransactionalEventListener(AFTER_COMMIT)} 가
 *       TX sync 로 등록되고, 커밋 후 발화된다.</li>
 *   <li>timeout=5s — 비정상 hang 시 {@code TransactionTimedOutException} 발화.</li>
 * </ul>
 *
 * <p>AFTER_COMMIT 이후 채널 적재는 PCS-11 의 {@code InboxReadyEventHandler} 가 담당한다.
 *
 * <p>TX 외부에서 publishEvent 를 호출하면 AFTER_COMMIT 리스너가 등록되지 않아 채널 적재가 0이 된다.
 * 이 경우 좀비 폴링(PCS-13)이 60s 임계로 회수하는 fallback 경로로 진행된다.
 */
@Service
@RequiredArgsConstructor
public class PgInboxPendingService {

    private final PgInboxRepository pgInboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * PENDING INSERT + AFTER_COMMIT publishEvent 를 단일 TX 안에서 수행한다.
     *
     * <p>orderId UNIQUE 충돌 시 기존 row id 를 반환하고 동일한 {@link PgInboxReadyEvent} 를 발행한다
     * (멱등 보장 — 중복 Kafka 메시지 재컨슘 시 채널 재적재 허용, 워커 TX_A SKIP LOCKED 가 흡수).
     *
     * @param orderId    주문 식별자 (pg_inbox.order_id UNIQUE)
     * @param amount     원화 최소 단위 정수
     * @param eventUuid  PG 콜백 이벤트 UUID (중복 방어용)
     * @param vendorType 벤더 타입 문자열 (e.g., "TOSS_PAYMENTS")
     * @param paymentKey 벤더 결제 키
     * @return 삽입 또는 기존 row 의 id
     */
    @Transactional(propagation = Propagation.REQUIRED, timeout = 5)
    public Long insertPendingAndPublish(
            String orderId,
            long amount,
            String eventUuid,
            String vendorType,
            String paymentKey
    ) {
        Long inboxId = pgInboxRepository.insertPending(orderId, amount, eventUuid, vendorType, paymentKey);
        applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inboxId));
        return inboxId;
    }
}
