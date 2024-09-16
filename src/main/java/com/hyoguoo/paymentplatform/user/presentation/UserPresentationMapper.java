package com.hyoguoo.paymentplatform.user.presentation;

import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserPresentationMapper {

    public static UserInfoResponse toUserInfoResponse(User user) {
        return UserInfoResponse.builder()
                .id(user.getId())
                .build();
    }
}
