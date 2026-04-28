package com.hyoguoo.paymentplatform.product.exception.common;

import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.product.presentation.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * product-service 전역 예외 처리기.
 * 도메인 오류는 상태 코드와 간결 메시지로 응답하고, 스택 트레이스는 찍지 않는다.
 * 예기치 못한 오류만 ERROR 로그를 남긴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException e) {
        LogFmt.warn(log, LogDomain.PRODUCT, EventType.EXCEPTION,
                () -> "product_not_found code=" + e.getErrorCode().getCode() + " message=" + e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e) {
        LogFmt.error(log, LogDomain.GLOBAL, EventType.EXCEPTION, e::getMessage);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("PRODUCT_INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."));
    }
}
