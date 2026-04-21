package com.hyoguoo.paymentplatform.user.application.port.out;

import com.hyoguoo.paymentplatform.user.domain.User;
import java.util.Optional;

/**
 * 사용자 outbound 포트.
 * T3-02 스캐폴드 — DB 어댑터 구현은 T3-03에서 완성.
 */
public interface UserRepository {

    Optional<User> findById(Long id);
}
