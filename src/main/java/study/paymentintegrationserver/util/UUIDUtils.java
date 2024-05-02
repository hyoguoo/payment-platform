package study.paymentintegrationserver.util;

public final class UUIDUtils {

    private UUIDUtils() {
        throw new AssertionError();
    }

    public static String generateUUID() {
        return java.util.UUID.randomUUID().toString();
    }
}
