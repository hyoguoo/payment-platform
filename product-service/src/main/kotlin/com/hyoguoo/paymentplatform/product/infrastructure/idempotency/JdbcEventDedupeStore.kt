package com.hyoguoo.paymentplatform.product.infrastructure.idempotency

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore
import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

/**
 * EventDedupeStore JDBC 구현체.
 *
 * V1__product_schema.sql 의 `stock_commit_dedupe` 테이블을 단순 dedupe 용도로 사용한다.
 * 테이블 schema 는 (event_uuid, order_id, product_id, qty, expires_at, created_at) 이지만
 * 본 어댑터는 `event_uuid + expires_at` 만 사용한다 — 나머지 컬럼은 NULL 허용이다.
 *
 * 전략:
 * - recordIfAbsent: 만료 엔트리 DELETE(expires_at < now, 호출자 주입) → INSERT IGNORE.
 *   INSERT IGNORE 가 0 row 영향이면 중복(false), 1 row 이면 최초(true).
 * - deleteExpired: expires_at < :now 조건 idempotent batch DELETE (LIMIT :batchSize).
 *   NamedParameterJdbcTemplate 을 사용해 LIMIT 바인딩을 안전하게 처리한다.
 *
 * raw-JDBC 경로 UTC 규약: Timestamp.from(instant) 바인딩 시 명시 UTC Calendar 를 사용한다.
 * 비-UTC JVM TZ 환경에서도 DB 저장 절대시점이 정확히 보존된다.
 * datasource URL 에 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` 설정 필수.
 *
 * 호출자(StockCommitUseCase)의 `@Transactional` 안에서 호출되므로 같은 트랜잭션에 참여한다.
 *
 * JdbcTemplate.update 에 넘기는 바인딩은 PreparedStatementSetter 로 명시한다. 코틀린 람다를 그대로 넘기면
 * update(String, vararg Any?) 오버로드가 골라져 람다 객체가 SQL 파라미터로 바인딩될 수 있다.
 */
@Repository
class JdbcEventDedupeStore(
    private val jdbcTemplate: JdbcTemplate,
    private val namedParameterJdbcTemplate: NamedParameterJdbcTemplate,
) : EventDedupeStore {
    /**
     * eventUUID 를 기록하고, 최초 기록이면 true 를 반환한다.
     * 이미 유효한 중복이면 false 를 반환한다.
     * 만료된 엔트리는 삭제 후 재삽입하여 true 를 반환한다.
     *
     * now 는 호출자(컨슈머 진입점)가 주입. 구현체는 내부에서 시각을 생성하지 않는다.
     * DELETE 조건 `expires_at < now`: 경계 동치(expires_at == now) 는 만료로 보지 않는다.
     * UTC Calendar 명시 바인딩: 비-UTC JVM TZ에서도 now/expires_at 절대시점 정확 보존.
     *
     * eventUUID 가 null 이면 만료 삭제 조건(`event_uuid = NULL`)이 어떤 행과도 맞지 않고, INSERT IGNORE 가
     * NOT NULL 기본 키에 `''` 를 넣는다(지금 동작, docs/TODOS.md STOCK-COMMIT-NULL-KEY).
     */
    override fun recordIfAbsent(
        eventUUID: String?,
        now: Instant,
        expiresAt: Instant,
    ): Boolean {
        val nowTs = Timestamp.from(now)
        jdbcTemplate.update(
            SQL_DELETE_EXPIRED_BY_UUID,
            PreparedStatementSetter { ps ->
                ps.setString(1, eventUUID)
                ps.setTimestamp(2, nowTs, UTC_CALENDAR)
            },
        )

        // INSERT IGNORE: 기존 유효 엔트리가 있으면 0, 없으면 1
        val expiresAtTs = Timestamp.from(expiresAt)
        val inserted =
            jdbcTemplate.update(
                SQL_INSERT_IGNORE,
                PreparedStatementSetter { ps ->
                    ps.setString(1, eventUUID)
                    ps.setTimestamp(2, expiresAtTs, UTC_CALENDAR)
                },
            )

        val isFirstSeen = inserted > 0
        LogFmt.debug(log, LogDomain.STOCK, EventType.EVENT_DEDUPE_RECORD) {
            "eventUUID=$eventUUID isFirstSeen=$isFirstSeen"
        }
        return isFirstSeen
    }

    /**
     * 만료된 dedupe 행을 일괄 삭제한다.
     * expires_at < :now 조건의 idempotent batch DELETE.
     *
     * LIMIT :batchSize 로 한 번에 삭제할 최대 행 수를 제한한다.
     * 이미 삭제된 행은 0 row affected — 동시 실행 무해.
     */
    override fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int {
        // NamedParameterJdbcTemplate 경로: connectionTimeZone=UTC datasource 파라미터로
        // 1차 보정됨. payment-service 패턴과 동일(JdbcPaymentEventDedupeStore.deleteExpired 참고).
        val params =
            MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("batchSize", batchSize)
        return namedParameterJdbcTemplate.update(SQL_DELETE_EXPIRED, params)
    }

    private companion object {
        const val SQL_INSERT_IGNORE =
            "INSERT IGNORE INTO stock_commit_dedupe (event_uuid, expires_at) VALUES (?, ?)"

        const val SQL_DELETE_EXPIRED_BY_UUID =
            "DELETE FROM stock_commit_dedupe WHERE event_uuid = ? AND expires_at < ?"

        const val SQL_DELETE_EXPIRED =
            "DELETE FROM stock_commit_dedupe " +
                "WHERE expires_at < :now " +
                "LIMIT :batchSize"

        val log = LoggerFactory.getLogger(JdbcEventDedupeStore::class.java)

        /** UTC Calendar 상수: raw-JDBC Timestamp 바인딩 시 비-UTC JVM TZ 오차 방지. */
        val UTC_CALENDAR: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    }
}
