package com.hyoguoo.paymentplatform.payment.core.common.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;

import jakarta.persistence.EntityListeners;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Column(name = "created_at", columnDefinition = "datetime(6)", updatable = false)
    @CreatedDate
    private Instant createdAt;

    @Column(name = "updated_at", columnDefinition = "datetime(6)")
    @LastModifiedDate
    private Instant updatedAt;

    @Column(name = "deleted_at", columnDefinition = "datetime(6)")
    private Instant deletedAt;
}
