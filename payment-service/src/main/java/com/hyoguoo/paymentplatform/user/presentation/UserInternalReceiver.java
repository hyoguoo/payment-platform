package com.hyoguoo.paymentplatform.user.presentation;

import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import com.hyoguoo.paymentplatform.user.presentation.port.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserInternalReceiver {

    private final UserService userService;

    public UserInfoResponse getUserInfoById(Long userId) {
        User user = userService.getById(userId);

        return UserPresentationMapper.toUserInfoResponse(user);
    }
}
