package com.hyoguoo.paymentplatform.product.exception.common

import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException
import com.hyoguoo.paymentplatform.product.presentation.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ProductNotFoundException::class)
    fun handleProductNotFound(e: ProductNotFoundException): ResponseEntity<ErrorResponse> {
        LogFmt.warn(log, LogDomain.PRODUCT, EventType.EXCEPTION) {
            "product_not_found code=${e.errorCode.code} message=${e.message}"
        }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(e.errorCode.code, e.message))
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleRuntime(e: RuntimeException): ResponseEntity<ErrorResponse> {
        LogFmt.error(log, LogDomain.GLOBAL, EventType.EXCEPTION) { e.message }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of("PRODUCT_INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."))
    }

    private companion object {
        val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
