package project.study.user.controller;

// AUTH-DISABLED: 소셜 로그인은 후순위로 미뤄짐 (ADR-0004) — 재도입 시 feature/BY-383-auth-contract 브랜치 참고
//
// import org.springframework.core.Ordered;
// import org.springframework.core.annotation.Order;
// import org.springframework.http.HttpStatus;
// import org.springframework.web.bind.annotation.ExceptionHandler;
// import org.springframework.web.bind.annotation.ResponseStatus;
// import org.springframework.web.bind.annotation.RestControllerAdvice;
// import project.study.common.ErrorResponse;
// import project.study.user.oauth.InvalidOAuthTokenException;
// import project.study.user.service.InvalidRefreshTokenException;
//
// @RestControllerAdvice
// @Order(Ordered.HIGHEST_PRECEDENCE)
// public class AuthExceptionHandler {
//
//     @ExceptionHandler({InvalidOAuthTokenException.class, InvalidRefreshTokenException.class})
//     @ResponseStatus(HttpStatus.UNAUTHORIZED)
//     public ErrorResponse handleInvalidToken(RuntimeException e) {
//         return new ErrorResponse(e.getMessage());
//     }
// }
