package com.hyoguoo.paymentplatform.user.presentation.dto;

import com.hyoguoo.paymentplatform.user.domain.User;
import java.time.LocalDateTime;

/**
 * 사용자 조회 결과 DTO.
 * domain → presentation 계층 전달 레코드.
 */
public record UserQueryResult(
        Long id,
        String email,
        LocalDateTime createdAt
) {

    public static UserQueryResult from(User user) {
        return new UserQueryResult(user.getId(), user.getEmail(), user.getCreatedAt());
    }
}
