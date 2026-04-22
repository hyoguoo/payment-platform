package com.hyoguoo.paymentplatform.user.exception.common;

import com.hyoguoo.paymentplatform.user.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.user.presentation.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * user-service 전역 예외 처리기.
 * 도메인 오류는 상태 코드와 간결 메시지로 응답하고, 스택 트레이스는 찍지 않는다.
 * 예기치 못한 오류만 ERROR 로그를 남긴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        log.warn("user_not_found code={} message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e) {
        log.error("user_internal_error message={}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("USER_INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."));
    }
}
