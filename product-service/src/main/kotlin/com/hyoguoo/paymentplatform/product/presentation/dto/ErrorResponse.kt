package com.hyoguoo.paymentplatform.product.presentation.dto

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    val code: String,
    val message: String?,
) {
    companion object {
        fun of(
            code: String,
            message: String?,
        ): ErrorResponse = ErrorResponse(code, message)
    }
}
