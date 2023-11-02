package study.paymentintegrationserver.util;

import java.util.Base64;

public class EncodeUtils {

    private EncodeUtils() {
        throw new AssertionError();
    }

    public static String encodeBase64(String str) {
        return Base64.getEncoder().encodeToString(str.getBytes());
    }
}

