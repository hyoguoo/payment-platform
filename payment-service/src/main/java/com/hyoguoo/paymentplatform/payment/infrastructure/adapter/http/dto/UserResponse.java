package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

/**
 * user-service GET /api/v1/users/{id} 응답 DTO.
 */
public record UserResponse(Long id, String email, String createdAt) {}
