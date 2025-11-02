package com.hyoguoo.paymentplatform.payment.exception.common;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.response.ErrorResponse;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentRetryableValidateException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossConfirmException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
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
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentFoundException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentStatusException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentStatusException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentValidException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentValidException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentTossNonRetryableException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentTossNonRetryableException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentTossRetryableException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentTossRetryableException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentTossConfirmException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentTossConfirmException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentRetryableValidateException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentRetryableValidateException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }

    @ExceptionHandler(PaymentOrderedProductStockException.class)
    public ResponseEntity<ErrorResponse> catchRuntimeException(PaymentOrderedProductStockException e) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(
                                e.getCode(),
                                e.getMessage()
                        )
                );
    }
}

