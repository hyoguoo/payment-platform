package com.hyoguoo.paymentplatform.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BasicResponse<T> {

    private T data;
    private ErrorResponse error;
}
