package com.hyoguoo.paymentplatform.user.infrastructure.repository;

import com.hyoguoo.paymentplatform.user.application.port.out.UserRepository;
import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.infrastructure.entity.UserEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * UserRepository 포트 JPA 어댑터.
 * JpaUserRepository → UserEntity → domain User 매핑만 담당.
 */
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
}
