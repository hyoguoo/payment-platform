package com.hyoguoo.paymentplatform.payment.exception.common;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.response.ErrorResponse;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.UserNotFoundException;
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
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentFound(PaymentFoundException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentStatusException.class)
    public ResponseEntity<ErrorResponse> handlePaymentStatus(PaymentStatusException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentValidException.class)
    public ResponseEntity<ErrorResponse> handlePaymentValid(PaymentValidException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentOrderedProductStockException.class)
    public ResponseEntity<ErrorResponse> handlePaymentOrderedProductStock(PaymentOrderedProductStockException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }
}

