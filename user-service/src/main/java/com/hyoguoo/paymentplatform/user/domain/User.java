package com.hyoguoo.paymentplatform.user.domain;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * 사용자 애그리거트 루트.
 * payment-service의 user 도메인에서 복사 이관 (T3-02).
 * payment-service 원본 삭제는 후속 태스크(T3-06) 범위.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
public class User {

    private Long id;
    private String email;
    private LocalDateTime createdAt;
}
