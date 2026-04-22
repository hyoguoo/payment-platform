package com.hyoguoo.paymentplatform.user.infrastructure.entity;

import com.hyoguoo.paymentplatform.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * user-service JPA 엔티티.
 * V1__user_schema.sql의 {@code user} 테이블과 매핑된다.
 * ddl-auto=validate → 스키마 드리프트가 있으면 기동 시 실패.
 */
@Getter
@Entity
@Table(name = "user")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public User toDomain() {
        return User.allArgsBuilder()
                .id(id)
                .email(email)
                .createdAt(createdAt)
                .allArgsBuild();
    }
}
