package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;

public interface UserPort {

    UserInfo getUserInfoById(Long userId);
}
