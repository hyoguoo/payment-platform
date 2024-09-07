package com.hyoguoo.paymentplatform.user.presentation.port;

import com.hyoguoo.paymentplatform.user.domain.User;

public interface UserService {

    User getById(Long id);
}
