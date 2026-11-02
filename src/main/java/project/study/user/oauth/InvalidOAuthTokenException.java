package project.study.user.oauth;

public class InvalidOAuthTokenException extends RuntimeException {

    public InvalidOAuthTokenException(String message) {
        super(message);
    }
}
