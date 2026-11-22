package com.hyoguoo.paymentplatform.product.presentation.dto

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class ErrorResponse(
    val code: String,
    val message: String?,
) {
    companion object {
        @JvmStatic
        fun of(
            code: String,
            message: String?,
        ): ErrorResponse = ErrorResponse(code, message)
    }
}
