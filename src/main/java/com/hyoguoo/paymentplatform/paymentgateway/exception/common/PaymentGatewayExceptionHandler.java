package com.hyoguoo.paymentplatform.paymentgateway.exception.common;

import com.hyoguoo.paymentplatform.core.response.ErrorResponse;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayFoundException;
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
public class PaymentGatewayExceptionHandler {

    @ExceptionHandler(PaymentGatewayFoundException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentGatewayFoundException e) {
        log.warn(e.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }
}

