package com.hyoguoo.paymentplatform.core.response;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ResponseUtil {

    public static <T> BasicResponse<T> success(T response) {
        return new BasicResponse<>(response, null);
    }

    public static <T> BasicResponse<T> error(ErrorResponse e) {
        return new BasicResponse<>(null, e);
    }
}
