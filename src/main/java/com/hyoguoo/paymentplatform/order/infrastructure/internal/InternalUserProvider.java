package com.hyoguoo.paymentplatform.order.infrastructure.internal;

import com.hyoguoo.paymentplatform.order.application.port.UserProvider;
import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.order.infrastructure.OrderInfrastructureMapper;
import com.hyoguoo.paymentplatform.user.presentation.UserInternalReceiver;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoClientResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalUserProvider implements UserProvider {

    private final UserInternalReceiver userInternalReceiver;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        UserInfoClientResponse userInfoClientResponse = userInternalReceiver.getUserInfoById(
                userId
        );

        return OrderInfrastructureMapper.toUserInfo(userInfoClientResponse);
    }
}
