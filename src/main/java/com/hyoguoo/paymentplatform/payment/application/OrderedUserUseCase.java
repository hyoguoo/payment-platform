package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderedUserUseCase {

    private final UserProvider userProvider;

    public UserInfo getUserInfoById(Long userId) {
        return userProvider.getUserInfoById(userId);
    }
}
