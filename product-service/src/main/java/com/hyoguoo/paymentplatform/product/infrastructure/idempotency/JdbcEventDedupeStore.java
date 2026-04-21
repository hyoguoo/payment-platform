package com.hyoguoo.paymentplatform.product.infrastructure.idempotency;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * EventDedupeStore JDBC 구현체.
 * <p>
 * V1__product_schema.sql의 product_event_dedupe 테이블 사용.
 * <p>
 * product_event_dedupe(event_uuid CHAR(36) PK, expires_at DATETIME(6))
 * <p>
 * 전략:
 * - existsValid: SELECT + expires_at 비교 (만료 여부 확인)
 * - recordIfAbsent: INSERT IGNORE (최초) 또는 UPDATE expires_at (TTL 만료 후 재활용)
 *   INSERT IGNORE가 0 row 영향이면 중복(false), 1 row이면 최초(true).
 *   단, 만료된 엔트리는 UPDATE로 덮어쓰고 true를 반환한다.
 * <p>
 * TTL 만료 재처리:
 * 만료된 row(expires_at &lt; NOW())는 DELETE 후 INSERT 방식으로 재활용.
 * @Transactional이 StockRestoreUseCase에 있으므로 이 메서드는 같은 트랜잭션에 참여한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcEventDedupeStore implements EventDedupeStore {

    private static final String SQL_EXISTS_VALID =
            "SELECT COUNT(*) FROM product_event_dedupe "
                    + "WHERE event_uuid = ? AND expires_at >= NOW(6)";

    private static final String SQL_INSERT_IGNORE =
            "INSERT IGNORE INTO product_event_dedupe (event_uuid, expires_at) VALUES (?, ?)";

    private static final String SQL_DELETE_EXPIRED =
            "DELETE FROM product_event_dedupe WHERE event_uuid = ? AND expires_at < NOW(6)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * eventUuid가 유효하게(TTL 미만료) 존재하는지 확인한다.
     * 만료된 엔트리는 존재하지 않는 것으로 간주한다.
     *
     * @param eventUuid 이벤트 식별자
     * @return 유효한 중복이면 true, 없거나 만료됐으면 false
     */
    @Override
    public boolean existsValid(String eventUuid) {
        Integer count = jdbcTemplate.queryForObject(SQL_EXISTS_VALID, Integer.class, eventUuid);
        return count != null && count > 0;
    }

    /**
     * eventUuid를 기록하고, 최초 기록이면 true를 반환한다.
     * 이미 유효한 중복이면 false를 반환한다.
     * 만료된 엔트리는 삭제 후 재삽입하여 true를 반환한다.
     *
     * @param eventUuid  이벤트 식별자
     * @param expiresAt  만료 시각 (TTL)
     * @return 최초 기록(또는 만료 후 재기록)이면 true, 유효한 중복이면 false
     */
    @Override
    public boolean recordIfAbsent(String eventUuid, Instant expiresAt) {
        // 만료된 엔트리 삭제 (없으면 0 row 영향, 있으면 삭제)
        jdbcTemplate.update(SQL_DELETE_EXPIRED, eventUuid);

        // INSERT IGNORE: 기존 유효 엔트리가 있으면 0, 없으면 1
        int inserted = jdbcTemplate.update(SQL_INSERT_IGNORE, eventUuid,
                java.sql.Timestamp.from(expiresAt));

        boolean isFirstSeen = inserted > 0;
        log.debug("JdbcEventDedupeStore: recordIfAbsent eventUuid={} isFirstSeen={}",
                eventUuid, isFirstSeen);
        return isFirstSeen;
    }
}
