package project.study.user.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.service.InvalidRefreshTokenException;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler({InvalidOAuthTokenException.class, InvalidRefreshTokenException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> handleInvalidToken(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
