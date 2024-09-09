package com.hyoguoo.paymentplatform.user.presentation;

import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoClientResponse;
import com.hyoguoo.paymentplatform.user.presentation.port.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserInternalReceiver {

    private final UserService userService;

    public UserInfoClientResponse getUserInfoById(Long userId) {
        User user = userService.getById(userId);

        return UserPresentationMapper.toUserInfoClientResponse(user);
    }
}
