package project.study.user.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.study.common.ErrorResponse;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.service.InvalidRefreshTokenException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    // Map 대신 record를 반환해야 Swagger 문서에 additionalProp 같은 익명 스키마 대신 ErrorResponse가 잡힌다
    @ExceptionHandler({InvalidOAuthTokenException.class, InvalidRefreshTokenException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidToken(RuntimeException e) {
        return new ErrorResponse(e.getMessage());
    }
}
