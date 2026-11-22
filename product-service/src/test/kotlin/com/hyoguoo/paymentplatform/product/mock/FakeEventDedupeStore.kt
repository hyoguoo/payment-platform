package com.hyoguoo.paymentplatform.product.mock

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * EventDedupeStore Fake — in-memory TTL 시뮬레이션 구현체 (product-service 테스트 전용).
 *
 * UUID dedupe + Redis TTL 보상 로직 테스트 지원.
 * 공통 jar 금지 정책에 따라 payment-service / pg-service 의 FakeEventDedupeStore 와 서비스별로 따로 둔다.
 *
 * TTL 만료 시뮬레이션:
 * - recordIfAbsent 호출 시 기존 엔트리의 expiresAt 가 now(호출자 주입) 이전이면 만료로 간주,
 *   덮어쓰기 후 true 를 반환한다.
 * - 내부 Clock 보유 제거 — now 는 호출자(컨슈머 진입점)가 단일 시각으로 산출해 주입한다.
 *
 * 키가 없는(null) 이벤트는 운영 저장소처럼 빈 문자열 키로 기록한다.
 *
 * Thread-safe: ConcurrentHashMap.compute 원자적 갱신.
 */
class FakeEventDedupeStore : EventDedupeStore {
    private val store = ConcurrentHashMap<String, Instant>()

    /**
     * eventUUID 를 기록하고, 최초 기록이면 true 를 반환한다.
     * 이미 존재하지만 만료된 경우(expiresAt < now) 덮어쓰기 후 true 를 반환한다.
     * 유효한 중복이면 false 를 반환한다.
     *
     * @param eventUUID 이벤트 식별자
     * @param now 현재 시각 — 만료 경계 판정 기준 (호출자 주입)
     * @param expiresAt 만료 시각 (TTL)
     */
    override fun recordIfAbsent(
        eventUUID: String?,
        now: Instant,
        expiresAt: Instant,
    ): Boolean {
        var recorded = false
        store.compute(eventUUID.orEmpty()) { _, existing ->
            if (existing == null || existing.isBefore(now)) {
                recorded = true
                expiresAt
            } else {
                recorded = false
                existing
            }
        }
        return recorded
    }

    /**
     * 만료된 dedupe 행을 일괄 삭제한다.
     * Fake 구현 — 삭제하지 않고 0 을 반환한다.
     *
     * @param now 현재 시각
     * @param batchSize 최대 삭제 건수
     * @return 삭제된 항목 수
     */
    override fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int = 0

    // --- 검증 헬퍼 ---

    fun contains(eventUUID: String): Boolean = store.containsKey(eventUUID)

    fun size(): Int = store.size

    fun reset() {
        store.clear()
    }
}
