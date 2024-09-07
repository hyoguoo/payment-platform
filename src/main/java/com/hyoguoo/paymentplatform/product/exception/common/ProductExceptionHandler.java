package com.hyoguoo.paymentplatform.product.exception.common;

import com.hyoguoo.paymentplatform.core.response.ErrorResponse;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
@RestControllerAdvice
public class ProductExceptionHandler {

    @ExceptionHandler(ProductStockException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(ProductStockException e) {
        log.warn(e.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }
}

