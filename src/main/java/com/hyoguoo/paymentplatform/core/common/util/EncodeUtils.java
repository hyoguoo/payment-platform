package com.hyoguoo.paymentplatform.core.common.util;

import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class EncodeUtils {

    public String encodeBase64(String src) {
        return Base64.getEncoder().encodeToString(src.getBytes());
    }
}
