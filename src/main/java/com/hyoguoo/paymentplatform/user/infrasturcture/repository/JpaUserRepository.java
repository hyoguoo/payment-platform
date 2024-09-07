package com.hyoguoo.paymentplatform.user.infrasturcture.repository;

import com.hyoguoo.paymentplatform.user.infrasturcture.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaUserRepository extends JpaRepository<UserEntity, Long> {

}
