package project.study.common.exception;

/** 429. 시도 횟수 제한에 걸렸다. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
