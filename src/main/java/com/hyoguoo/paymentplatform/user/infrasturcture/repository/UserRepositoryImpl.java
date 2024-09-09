package com.hyoguoo.paymentplatform.user.infrasturcture.repository;

import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.infrasturcture.entity.UserEntity;
import com.hyoguoo.paymentplatform.user.application.port.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final JpaUserRepository jpaUserRepository;

    @Override
    public Optional<User> findById(Long id) {
        return jpaUserRepository
                .findById(id)
                .map(UserEntity::toDomain);
    }

    @Override
    public User saveOrUpdate(User user) {
        return jpaUserRepository.save(UserEntity.from(user)).toDomain();
    }
}
