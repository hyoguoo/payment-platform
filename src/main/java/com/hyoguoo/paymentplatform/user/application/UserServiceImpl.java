package com.hyoguoo.paymentplatform.user.application;

import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.exception.UserFoundException;
import com.hyoguoo.paymentplatform.user.exception.common.UserErrorCode;
import com.hyoguoo.paymentplatform.user.presentation.port.UserService;
import com.hyoguoo.paymentplatform.user.application.port.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    public User getById(Long id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> UserFoundException.of(UserErrorCode.USER_NOT_FOUND));
    }
}
