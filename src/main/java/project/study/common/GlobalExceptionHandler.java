package project.study.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(BadRequestException e) {
        return new ErrorResponse(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NotFoundException e) {
        return new ErrorResponse(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(ConflictException e) {
        return new ErrorResponse(ErrorCode.CONFLICT, e.getMessage());
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse(ErrorCode.BAD_REQUEST, "요청 본문을 읽을 수 없습니다");
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
