package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;

public interface UserProvider {

    UserInfo getUserInfoById(Long userId);
}
