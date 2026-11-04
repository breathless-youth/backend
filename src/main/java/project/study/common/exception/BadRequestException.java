package project.study.common.exception;

public class BadRequestException extends RuntimeException {

    private final ErrorCode code;

    /** 클라이언트가 400끼리 갈라 볼 필요가 없는 경우 — 공통 BAD_REQUEST로 내려간다 */
    public BadRequestException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }

    public BadRequestException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
