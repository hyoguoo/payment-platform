package com.hyoguoo.paymentplatform.user.infrastructure.repository;

import com.hyoguoo.paymentplatform.user.infrastructure.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaUserRepository extends JpaRepository<UserEntity, Long> {

}
