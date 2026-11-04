package project.study.common.exception;

import java.sql.SQLException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String MISSING_USER_DETAIL = "is not present in table \"users\"";

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(BadRequestException e) {
        return new ErrorResponse(e.getCode(), e.getMessage());
    }

    // 403은 @ResponseStatus가 아닌 ResponseEntity로 내린다 — springdoc이 @ResponseStatus 핸들러의 상태를 모든 오퍼레이션에
    // 일반 응답으로 붙이는데, 403은 그룹 API에서만 쓰여 무관한 기존 API 문서까지 어긋난다 (그룹 API는 @ApiResponse로 직접 적는다)
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException e) {
        return new ErrorResponse(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(ConflictException e) {
        return new ErrorResponse(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError == null) {
            return new ErrorResponse(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다");
        }
        return new ErrorResponse(
                ErrorCode.VALIDATION_FAILED, fieldError.getField() + ": " + fieldError.getDefaultMessage());
    }

    // 경로변수·쿼리 파라미터의 타입 불일치(Long 자리에 문자열 등)는 클라이언트 잘못이다 — 아래 generic 핸들러가
    // DefaultHandlerExceptionResolver보다 먼저 잡아 500·Sentry로 새는 것을 막는다
    @ExceptionHandler(TypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(TypeMismatchException e) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName() : e.getPropertyName();
        return new ErrorResponse(ErrorCode.BAD_REQUEST, (name != null ? name + ": " : "") + "값의 형식이 올바르지 않습니다");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse(ErrorCode.BAD_REQUEST, "요청 본문을 읽을 수 없습니다");
    }

    /**
     * 지워진 사용자를 가리키는 쓰기의 FK 위반은 404 {@code USER_NOT_FOUND}다 — 탈퇴·병합 뒤 최대 30분 남는 옛 access 토큰(ADR-0042)으로 온
     * 쓰기라 서버 오류가 아니다. 그 밖의 무결성 위반(다른 FK, unique, users 행을 지우다 걸린 참조)은 지금처럼 500이다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        if (referencesMissingUser(e)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다"));
        }
        return handleUnexpected(e);
    }

    /**
     * PostgreSQL 외래 키 위반(SQLState 23503) 중 "넣으려는 행이 가리키는 users 행이 없다"인지. 드라이버(pgjdbc)는 런타임 의존성이라 표준
     * SQLException의 상태와, pgjdbc가 메시지에 붙이는 Detail({@code Key (user_id)=(5) is not present in table "users".})로 가른다. 서버
     * 메시지 언어가 바뀌어 문구가 달라지면 지금처럼 500으로 남을 뿐이다.
     */
    private static boolean referencesMissingUser(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (FOREIGN_KEY_VIOLATION.equals(s.getSQLState())
                            && s.getMessage() != null
                            && s.getMessage().contains(MISSING_USER_DETAIL)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 위의 어떤 핸들러도 처리하지 못한 예외를 마지막으로 받는다.
     * Sentry 전송은 여기서 하지 않는다 — prod의 {@code sentry.exception-resolver-order}를 음수로
     * 지정해 SentryExceptionResolver가 이 핸들러보다 먼저 실행되도록 해뒀으므로 (ADR-0011),
     * 여기서 또 호출하면 같은 예외가 이슈 2개로 중복된다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 405·415 같은 Spring MVC 표준 예외를 500으로 둔갑시키지 않는다.
        // 이 핸들러는 표준 예외를 담당하는 DefaultHandlerExceptionResolver보다 먼저 실행되므로
        // 여기서 걸러내지 않으면 상태코드가 뭉개진다.
        if (e instanceof org.springframework.web.ErrorResponse standard) {
            HttpStatusCode status = standard.getStatusCode();
            // 표준 예외라도 5xx면 내부 사정을 노출하지 않는 서버 오류 메시지로 통일한다
            if (status.is5xxServerError()) {
                return ResponseEntity.status(status).body(new ErrorResponse(ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다"));
            }
            return ResponseEntity.status(status).body(new ErrorResponse(ErrorCode.REQUEST_FAILED, "요청을 처리할 수 없습니다"));
        }
        // 내부 예외 메시지는 노출하지 않는다
        return ResponseEntity.internalServerError().body(new ErrorResponse(ErrorCode.INTERNAL_ERROR, "서버 오류가 발생했습니다"));
    }
}
