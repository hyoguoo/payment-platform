package com.hyoguoo.paymentplatform.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyoguoo.paymentplatform.core.response.ErrorResponse;

@SuppressWarnings("unused")
public class BasicResponseMixin<T> {

    @JsonCreator
    public BasicResponseMixin(
            @JsonProperty("data") T data,
            @JsonProperty("error") ErrorResponse error
    ) {
    }
}
