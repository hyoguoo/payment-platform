package com.hyoguoo.paymentplatform.user.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
public class User {

    private Long id;
    private String username;
    private String email;
}
