package com.hyoguoo.paymentplatform.user.application.usecase;

import com.hyoguoo.paymentplatform.user.application.port.out.UserRepository;
import com.hyoguoo.paymentplatform.user.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.user.exception.common.UserErrorCode;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserQueryResult;
import com.hyoguoo.paymentplatform.user.presentation.port.UserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 조회 유스케이스.
 * UserRepository.findById → 없으면 UserNotFoundException, 있으면 UserQueryResult 반환.
 */
@Service
@RequiredArgsConstructor
public class UserQueryUseCase implements UserQueryService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserQueryResult queryById(long id) {
        return userRepository.findById(id)
                .map(UserQueryResult::from)
                .orElseThrow(() -> UserNotFoundException.of(UserErrorCode.USER_NOT_FOUND));
    }
}
