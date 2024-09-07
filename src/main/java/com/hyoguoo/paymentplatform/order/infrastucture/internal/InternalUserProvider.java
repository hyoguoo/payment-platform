package com.hyoguoo.paymentplatform.order.infrastucture.internal;

import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.order.application.port.UserProvider;
import com.hyoguoo.paymentplatform.user.presentation.port.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalUserProvider implements UserProvider {

    private final UserService userService;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        return UserInfo.builder()
                .id(userService.getById(userId).getId())
                .build();
    }
}
