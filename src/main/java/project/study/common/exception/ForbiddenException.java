package project.study.common.exception;

/** 로그인은 돼 있지만 이 일을 할 자격이 없다 (403) — 이유는 코드로 갈라 내려준다 */
public class ForbiddenException extends RuntimeException {

    private final ErrorCode code;

    public ForbiddenException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
