package com.hyoguoo.paymentplatform.core.exception;


import static com.hyoguoo.paymentplatform.core.exception.GlobalErrorCode.HEADER_NOT_FOUND;
import static com.hyoguoo.paymentplatform.core.exception.GlobalErrorCode.INTERNAL_SERVER_ERROR;
import static com.hyoguoo.paymentplatform.core.exception.GlobalErrorCode.INVALID_INPUT_VALUE;

import com.hyoguoo.paymentplatform.core.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(RuntimeException e) {
        log.error(e.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                                INTERNAL_SERVER_ERROR.getCode(),
                                INTERNAL_SERVER_ERROR.getMessage()
                        )
                );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> catchMissingRequestHeaderException(
            MissingRequestHeaderException e
    ) {
        log.warn(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                HEADER_NOT_FOUND.getCode(),
                                null
                        )
                );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> catchMethodArgumentNotValidException(
            MethodArgumentNotValidException e
    ) {
        log.warn(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                INVALID_INPUT_VALUE.getCode(),
                                null
                        )
                );
    }
}

