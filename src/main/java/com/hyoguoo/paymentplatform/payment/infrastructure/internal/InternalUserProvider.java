package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.user.presentation.UserInternalReceiver;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalUserProvider implements UserProvider {

    private final UserInternalReceiver userInternalReceiver;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        UserInfoResponse userInfoResponse = userInternalReceiver.getUserInfoById(userId);

        return PaymentInfrastructureMapper.toUserInfo(userInfoResponse);
    }
}
