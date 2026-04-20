package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.UserPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderedUserUseCase {

    private final UserPort userPort;

    public UserInfo getUserInfoById(Long userId) {
        return userPort.getUserInfoById(userId);
    }
}
