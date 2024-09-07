package com.hyoguoo.paymentplatform.user.service.port;

import com.hyoguoo.paymentplatform.user.domain.User;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(Long id);

    User saveOrUpdate(User user);
}
