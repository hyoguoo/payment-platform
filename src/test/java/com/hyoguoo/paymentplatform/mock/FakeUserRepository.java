package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.user.application.port.UserRepository;
import com.hyoguoo.paymentplatform.user.domain.User;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FakeUserRepository implements UserRepository {

    private final Map<Long, User> database = new HashMap<>();
    private Long autoGeneratedId = 1L;

    @Override
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(database.get(id));
    }

    @Override
    public User saveOrUpdate(User user) {
        if (user.getId() == null) {
            user = User.allArgsBuilder()
                    .id(autoGeneratedId)
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .allArgsBuild();
            autoGeneratedId++;
        }
        database.put(user.getId(), user);
        return user;
    }
}