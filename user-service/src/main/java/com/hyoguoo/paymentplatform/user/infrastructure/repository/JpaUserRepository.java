package com.hyoguoo.paymentplatform.user.infrastructure.repository;

import com.hyoguoo.paymentplatform.user.infrastructure.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA — user 테이블 직접 접근용. 어댑터 외부 노출 금지.
 */
public interface JpaUserRepository extends JpaRepository<UserEntity, Long> {
}
