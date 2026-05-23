package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaymentEventDedupeStore Fake — MySQL INSERT IGNORE 를 인메모리 Set 으로 시뮬레이션.
 *
 * <p>Thread-safe: {@link ConcurrentHashMap#newKeySet()} 기반 atomic {@code add}.
 * {@code add} 는 {@code putIfAbsent} 와 동일한 atomic 시맨틱을 제공하므로
 * INSERT IGNORE + affected row 반환 동작과 정합한다.
 *
 * <p>단위 테스트 assertion 헬퍼 ({@code clear} / {@code size} / {@code contains}) 를 제공한다.
 * JDBC 어댑터 단위 테스트와 유스케이스 테스트에서 소비.
 */
public class FakePaymentEventDedupeStore implements PaymentEventDedupeStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * {@inheritDoc}
     *
     * <p>eventUuid 가 이미 존재하면 0(중복), 신규이면 Set 에 추가 후 1(신규 마킹) 반환.
     */
    @Override
    public int markIfAbsent(String eventUuid, long orderId, String status, Instant expiresAt) {
        boolean inserted = seen.add(eventUuid);
        return inserted ? 1 : 0;
    }

    // --- assertion helpers ---

    /**
     * 누적 상태를 초기화한다. 각 테스트 메서드 setUp 에서 사용.
     */
    public void clear() {
        seen.clear();
    }

    /**
     * 현재 마킹된 eventUuid 개수를 반환한다.
     */
    public int size() {
        return seen.size();
    }

    /**
     * 해당 eventUuid 가 이미 마킹됐는지 확인한다.
     */
    public boolean contains(String eventUuid) {
        return seen.contains(eventUuid);
    }
}
