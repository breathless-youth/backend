package project.study.user.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ErrorResponse;
import project.study.user.oauth.InvalidOAuthTokenException;
import project.study.user.oauth.SocialProviderUnavailableException;
import project.study.user.service.AlreadyLinkedException;
import project.study.user.service.InvalidRefreshTokenException;

/**
 * 인증 도메인 예외 → refresh 실패 401, 소셜 토큰 실패 400, 프로바이더 장애 503, 이미 소셜 계정 409. {@code GlobalExceptionHandler}의 최종
 * {@code Exception} 핸들러(500)가 먼저 잡지 않도록 최상위 우선순위를 준다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return new ErrorResponse(ErrorCode.INVALID_REFRESH_TOKEN, e.getMessage());
    }

    // 401이 아니다 — 앱은 401이면 refresh부터 하므로, 우리 토큰 문제가 아닌 실패에 401을 쓰면 헛된 refresh가 돈다
    @ExceptionHandler(InvalidOAuthTokenException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidSocialToken(InvalidOAuthTokenException e) {
        return new ErrorResponse(ErrorCode.INVALID_SOCIAL_TOKEN, e.getMessage());
    }

    // 토큰이 아니라 프로바이더 쪽 장애다 — 400으로 내면 앱이 사용자를 소셜 로그인 처음으로 돌려보낸다
    @ExceptionHandler(SocialProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse handleSocialProviderUnavailable(SocialProviderUnavailableException e) {
        return new ErrorResponse(ErrorCode.SOCIAL_PROVIDER_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(AlreadyLinkedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleAlreadyLinked(AlreadyLinkedException e) {
        return new ErrorResponse(ErrorCode.ALREADY_LINKED, e.getMessage());
    }
}
