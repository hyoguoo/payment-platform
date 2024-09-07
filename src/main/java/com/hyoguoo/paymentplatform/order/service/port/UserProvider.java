package com.hyoguoo.paymentplatform.order.service.port;

import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;

public interface UserProvider {

    UserInfo getUserInfoById(Long userId);
}
