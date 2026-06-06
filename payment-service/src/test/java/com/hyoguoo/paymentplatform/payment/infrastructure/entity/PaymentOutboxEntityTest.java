package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentOutboxEntityTest {

    /**
     * P16 — BaseEntity getter 가 Instant 로 전환됐으므로 createdAt/updatedAt 이 toDomain 매핑 경계에서
     * 수동 변환 없이 동일 Instant 로 직접 전달됨을 단정한다.
     */
    @Test
    @DisplayName("toDomain — createdAt/updatedAt이 Instant로 직접 반환된다")
    void toDomain_createdAt_updatedAt이Instant로직접반환된다() throws Exception {
        PaymentOutboxEntity entity = PaymentOutboxEntity.builder()
                .orderId("order-1")
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .build();
        Instant createdAt = Instant.parse("2026-06-06T01:02:03.456789Z");
        Instant updatedAt = Instant.parse("2026-06-06T02:03:04.567890Z");
        setBaseField(entity, "createdAt", createdAt);
        setBaseField(entity, "updatedAt", updatedAt);

        PaymentOutbox domain = entity.toDomain();

        assertThat(domain.getCreatedAt()).isEqualTo(createdAt);
        assertThat(domain.getUpdatedAt()).isEqualTo(updatedAt);
    }

    /**
     * P16 — nextRetryAt 은 audit 컬럼이 아닌 비즈니스 컬럼(LocalDateTime 보관)이므로
     * toInstant 헬퍼를 경유해 UTC Instant 로 변환됨을 단정한다 (헬퍼 잔존 회귀 가드).
     */
    @Test
    @DisplayName("toDomain — nextRetryAt은 toInstant 헬퍼 경유로 UTC Instant 반환")
    void toDomain_nextRetryAt_toInstant헬퍼경유동작확인() {
        LocalDateTime nextRetryAt = LocalDateTime.of(2026, 6, 6, 3, 4, 5);
        PaymentOutboxEntity entity = PaymentOutboxEntity.builder()
                .orderId("order-2")
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(nextRetryAt)
                .build();

        PaymentOutbox domain = entity.toDomain();

        assertThat(domain.getNextRetryAt()).isEqualTo(nextRetryAt.toInstant(ZoneOffset.UTC));
    }

    private static void setBaseField(Object target, String name, Instant value) throws Exception {
        Field field = BaseEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
