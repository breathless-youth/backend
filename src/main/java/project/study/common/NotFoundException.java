package project.study.common;

public class NotFoundException extends RuntimeException {

    private final ErrorCode code;

    /** 클라이언트가 404끼리 갈라 볼 필요가 없는 경우 — 공통 NOT_FOUND로 내려간다 */
    public NotFoundException(String message) {
        this(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
