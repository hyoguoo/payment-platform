package com.hyoguoo.paymentplatform.user.presentation.dto;

import java.time.LocalDateTime;

/**
 * GET /api/v1/users/{id} 응답 DTO.
 */
public record UserResponse(
        Long id,
        String email,
        LocalDateTime createdAt
) {

    public static UserResponse from(UserQueryResult result) {
        return new UserResponse(result.id(), result.email(), result.createdAt());
    }
}
