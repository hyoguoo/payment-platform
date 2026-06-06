package com.hyoguoo.paymentplatform.payment.core.common.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P14 회귀 가드 — BaseEntity audit 3필드 타입이 {@code Instant} 이고, {@code createdAt} 의
 * {@code @Column(updatable = false)} 가 보존됨을 리플렉션으로 단정한다.
 *
 * <p>타입 전환 전(P14 RED 시점)에는 필드가 {@code LocalDateTime} 이므로 {@code audit3필드_타입이_Instant} 테스트가 FAIL.
 * P14 GREEN 후 두 테스트 모두 통과함으로써 D4 전환 회귀를 영구 차단한다.
 */
@DisplayName("P14 회귀 가드 — BaseEntity audit 필드 타입 및 createdAt updatable=false 단정")
class BaseEntityAuditTypeTest {

    /**
     * audit 3필드({@code createdAt}/{@code updatedAt}/{@code deletedAt})의 Java 필드 타입이
     * {@code java.time.Instant} 임을 리플렉션으로 단정한다.
     */
    @Test
    @DisplayName("audit 3필드(createdAt/updatedAt/deletedAt) 타입이 Instant다")
    void audit3필드_타입이_Instant() throws Exception {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        Field deletedAtField = BaseEntity.class.getDeclaredField("deletedAt");

        assertThat(createdAtField.getType())
                .as("createdAt 필드 타입은 Instant 여야 한다 (D4 전환)")
                .isEqualTo(Instant.class);
        assertThat(updatedAtField.getType())
                .as("updatedAt 필드 타입은 Instant 여야 한다 (D4 전환)")
                .isEqualTo(Instant.class);
        assertThat(deletedAtField.getType())
                .as("deletedAt 필드 타입은 Instant 여야 한다 (D4 전환)")
                .isEqualTo(Instant.class);
    }

    /**
     * {@code createdAt} 필드에 붙은 {@code @Column.updatable()} 이 {@code false} 임을 단정한다.
     *
     * <p>BaseEntity 타입 전환 시 이 속성이 실수로 제거되면 이 테스트가 FAIL 하여 audit 불변 회귀를 즉시 탐지한다.
     */
    @Test
    @DisplayName("createdAt 의 @Column.updatable=false 가 보존된다 (audit 불변 회귀 가드)")
    void createdAt_updatableFalse_보존() throws Exception {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Column column = createdAtField.getAnnotation(Column.class);

        assertThat(column)
                .as("createdAt 에 @Column 애너테이션이 있어야 한다")
                .isNotNull();
        assertThat(column.updatable())
                .as("createdAt @Column.updatable 은 false 여야 한다 — 생성 시각 갱신 금지 불변")
                .isFalse();
    }
}
