package project.study.user.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ErrorResponse;
import project.study.user.service.InvalidRefreshTokenException;

/**
 * 인증 도메인 예외 → 401. {@code GlobalExceptionHandler}의 최종 {@code Exception} 핸들러(500)가 먼저 잡지 않도록
 * 최상위 우선순위를 준다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return new ErrorResponse(ErrorCode.INVALID_REFRESH_TOKEN, e.getMessage());
    }
}
