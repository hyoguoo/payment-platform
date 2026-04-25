package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

/**
 * user-service GET /api/v1/users/{id} 응답 DTO.
 * F-13: UserHttpAdapter inline record → 독립 패키지 분리.
 */
public record UserResponse(Long id, String email, String createdAt) {}
