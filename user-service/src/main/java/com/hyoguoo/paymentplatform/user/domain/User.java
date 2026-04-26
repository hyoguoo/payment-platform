package com.hyoguoo.paymentplatform.user.domain;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * 사용자 애그리거트 루트.
 * payment-service 의 user 도메인에서 복제 이관됐다 — payment-service 원본 삭제는 별도 작업으로 분리한다.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
public class User {

    private Long id;
    private String email;
    private LocalDateTime createdAt;
}
