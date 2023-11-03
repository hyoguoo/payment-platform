package study.paymentintegrationserver.exception;

public class UserException extends RuntimeException {

    private UserException(UserErrorMessage userErrorMessage) {
        super(userErrorMessage.getMessage());
    }

    public static UserException of(UserErrorMessage userErrorMessage) {
        return new UserException(userErrorMessage);
    }
}
