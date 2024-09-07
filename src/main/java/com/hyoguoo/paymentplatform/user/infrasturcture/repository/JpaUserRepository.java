package com.hyoguoo.paymentplatform.user.infrasturcture.repository;

import com.hyoguoo.paymentplatform.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaUserRepository extends JpaRepository<User, Long> {

}
