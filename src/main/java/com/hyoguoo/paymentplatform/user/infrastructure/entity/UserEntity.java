package com.hyoguoo.paymentplatform.user.infrastructure.entity;

import com.hyoguoo.paymentplatform.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "email", nullable = false)
    private String email;

    public static UserEntity from(User user) {
        return UserEntity.builder()
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    public User toDomain() {
        return User.builder()
                .id(id)
                .username(username)
                .email(email)
                .build();
    }
}
