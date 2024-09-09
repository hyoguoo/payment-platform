package com.hyoguoo.paymentplatform.user.presentation;

import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoClientResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserPresentationMapper {

    public static UserInfoClientResponse toUserInfoClientResponse(User user) {
        return UserInfoClientResponse.builder()
                .id(user.getId())
                .build();
    }
}
