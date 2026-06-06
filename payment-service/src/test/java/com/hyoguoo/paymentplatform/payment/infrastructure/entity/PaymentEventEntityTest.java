package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentEventEntityTest {

    /**
     * P15 — BaseEntity getter 가 Instant 로 전환됐으므로 toDomain 매핑 경계에서
     * {@code .toInstant(ZoneOffset.UTC)} 수동 변환 없이 createdAt 이 동일 Instant 로 직접 전달됨을 단정한다.
     */
    @Test
    @DisplayName("toDomain — createdAt이 Instant로 직접 반환된다 (toInstant 변환 없음)")
    void toDomain_createdAt이Instant로직접반환된다() throws Exception {
        PaymentEventEntity entity = PaymentEventEntity.builder().build();
        Instant fixedCreatedAt = Instant.parse("2026-06-06T12:34:56.123456Z");

        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        createdAtField.setAccessible(true);
        createdAtField.set(entity, fixedCreatedAt);

        PaymentEvent domain = entity.toDomain(Collections.emptyList());

        assertThat(domain.getCreatedAt()).isEqualTo(fixedCreatedAt);
    }
}
