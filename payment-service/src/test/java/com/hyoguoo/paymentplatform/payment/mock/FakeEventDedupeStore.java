package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventDedupeStore Fake — in-memory 구현체 (payment-service 전용).
 * ADR-04(2단 멱등성 키): 메시지 레벨 eventUUID dedupe 테스트용.
 * ADR-30: pg-service의 FakeEventDedupeStore와 독립 복제 — 공통 lib 금지.
 *
 * <p>T-C3: Clock 주입으로 TTL 만료 시뮬레이션 지원.
 * markWithLease(shortTtl) → extendLease(longTtl) → remove 패턴 준수.
 *
 * <p>Thread-safe: ConcurrentHashMap 기반.
 */
public class FakeEventDedupeStore implements EventDedupeStore {

    /** eventUuid → 만료 시각. */
    private final ConcurrentHashMap<String, Instant> leaseMap = new ConcurrentHashMap<>();

    private final Clock clock;

    /** Clock 없이 생성 시 시스템 시계 사용 (레거시 테스트 호환). */
    public FakeEventDedupeStore() {
        this.clock = Clock.systemUTC();
    }

    public FakeEventDedupeStore(Clock clock) {
        this.clock = clock;
    }

    // -----------------------------------------------------------------------
    // T-C3 two-phase lease API
    // -----------------------------------------------------------------------

    @Override
    public boolean markWithLease(String eventUuid, Duration shortTtl) {
        Instant now = clock.instant();
        Instant expiry = now.plus(shortTtl);

        // 원자적으로: 키가 없거나 만료된 경우에만 등록
        Instant existing = leaseMap.get(eventUuid);
        if (existing != null && existing.isAfter(now)) {
            return false; // 아직 유효한 lease — 다른 consumer가 처리 중
        }
        leaseMap.put(eventUuid, expiry);
        return true;
    }

    @Override
    public boolean extendLease(String eventUuid, Duration longTtl) {
        Instant now = clock.instant();
        Instant existing = leaseMap.get(eventUuid);
        if (existing == null) {
            return false; // 키 없음
        }
        // 만료 여부와 관계없이 키가 존재하면 연장 (Redis XX 패턴: 키 존재 시)
        // 단, 만료된 경우는 "논리적으로 없음"으로 간주
        if (existing.isBefore(now)) {
            return false; // 이미 만료된 키는 연장 불가
        }
        leaseMap.put(eventUuid, now.plus(longTtl));
        return true;
    }

    @Override
    public boolean remove(String eventUuid) {
        return leaseMap.remove(eventUuid) != null;
    }

    // -----------------------------------------------------------------------
    // 검증 헬퍼
    // -----------------------------------------------------------------------

    public int size() {
        return leaseMap.size();
    }

    public boolean contains(String eventUuid) {
        Instant existing = leaseMap.get(eventUuid);
        if (existing == null) {
            return false;
        }
        return existing.isAfter(clock.instant());
    }

    // -----------------------------------------------------------------------
    // 초기화
    // -----------------------------------------------------------------------

    public void reset() {
        leaseMap.clear();
    }
}
