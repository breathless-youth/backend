package project.study.common.exception;

public class ConflictException extends RuntimeException {

    private final ErrorCode code;

    /** 클라이언트가 409끼리 갈라 볼 필요가 없는 경우 — 공통 CONFLICT로 내려간다 */
    public ConflictException(String message) {
        this(ErrorCode.CONFLICT, message);
    }

    public ConflictException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
