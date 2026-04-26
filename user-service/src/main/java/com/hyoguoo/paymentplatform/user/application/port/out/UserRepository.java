package com.hyoguoo.paymentplatform.user.application.port.out;

import com.hyoguoo.paymentplatform.user.domain.User;
import java.util.Optional;

/**
 * 사용자 outbound 포트.
 */
public interface UserRepository {

    Optional<User> findById(Long id);
}
