package com.hyoguoo.paymentplatform.product.infrastructure.idempotency;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * EventDedupeStore JDBC 구현체.
 * <p>
 * V1__product_schema.sql 의 {@code stock_commit_dedupe} 테이블을 단순 dedupe 용도로 사용한다.
 * 테이블 schema 는 (event_uuid, order_id, product_id, qty, expires_at, created_at) 이지만
 * 본 어댑터는 {@code event_uuid + expires_at} 만 사용한다 — 나머지 컬럼은 NULL 허용이다.
 * <p>
 * 전략:
 * - existsValid: SELECT + expires_at 비교 (만료 여부 확인)
 * - recordIfAbsent: 만료 엔트리 DELETE → INSERT IGNORE.
 *   INSERT IGNORE 가 0 row 영향이면 중복(false), 1 row 이면 최초(true).
 * <p>
 * 호출자(StockCommitUseCase)의 {@code @Transactional} 안에서 호출되므로 같은 트랜잭션에 참여한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcEventDedupeStore implements EventDedupeStore {

    private static final String SQL_EXISTS_VALID =
            "SELECT COUNT(*) FROM stock_commit_dedupe "
                    + "WHERE event_uuid = ? AND expires_at >= NOW()";

    private static final String SQL_INSERT_IGNORE =
            "INSERT IGNORE INTO stock_commit_dedupe (event_uuid, expires_at) VALUES (?, ?)";

    private static final String SQL_DELETE_EXPIRED =
            "DELETE FROM stock_commit_dedupe WHERE event_uuid = ? AND expires_at < NOW()";

    private final JdbcTemplate jdbcTemplate;

    /**
     * eventUUID 가 유효하게(TTL 미만료) 존재하는지 확인한다.
     * 만료된 엔트리는 존재하지 않는 것으로 간주한다.
     *
     * @param eventUUID 이벤트 식별자
     * @return 유효한 중복이면 true, 없거나 만료됐으면 false
     */
    @Override
    public boolean existsValid(String eventUUID) {
        Integer count = jdbcTemplate.queryForObject(SQL_EXISTS_VALID, Integer.class, eventUUID);
        return count != null && count > 0;
    }

    /**
     * eventUUID 를 기록하고, 최초 기록이면 true 를 반환한다.
     * 이미 유효한 중복이면 false 를 반환한다.
     * 만료된 엔트리는 삭제 후 재삽입하여 true 를 반환한다.
     *
     * @param eventUUID  이벤트 식별자
     * @param expiresAt  만료 시각 (TTL)
     * @return 최초 기록(또는 만료 후 재기록)이면 true, 유효한 중복이면 false
     */
    @Override
    public boolean recordIfAbsent(String eventUUID, Instant expiresAt) {
        // 만료된 엔트리 삭제 (없으면 0 row 영향)
        jdbcTemplate.update(SQL_DELETE_EXPIRED, eventUUID);

        // INSERT IGNORE: 기존 유효 엔트리가 있으면 0, 없으면 1
        int inserted = jdbcTemplate.update(SQL_INSERT_IGNORE, eventUUID,
                java.sql.Timestamp.from(expiresAt));

        boolean isFirstSeen = inserted > 0;
        LogFmt.debug(log, LogDomain.STOCK, EventType.EVENT_DEDUPE_RECORD,
                () -> "eventUUID=" + eventUUID + " isFirstSeen=" + isFirstSeen);
        return isFirstSeen;
    }
}
