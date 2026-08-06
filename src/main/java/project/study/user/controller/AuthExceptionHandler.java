package project.study.user.controller;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import org.springframework.http.HttpStatus;
// import org.springframework.web.bind.annotation.ExceptionHandler;
// import org.springframework.web.bind.annotation.ResponseStatus;
// import org.springframework.web.bind.annotation.RestControllerAdvice;
// import project.study.user.dto.ErrorResponse;
// import project.study.user.oauth.InvalidOAuthTokenException;
// import project.study.user.service.InvalidRefreshTokenException;
//
// @RestControllerAdvice
// public class AuthExceptionHandler {
//
//     // Map 대신 record를 반환해야 Swagger 문서에 additionalProp 같은 익명 스키마 대신 ErrorResponse가 잡힌다
//     @ExceptionHandler({InvalidOAuthTokenException.class, InvalidRefreshTokenException.class})
//     @ResponseStatus(HttpStatus.UNAUTHORIZED)
//     public ErrorResponse handleInvalidToken(RuntimeException e) {
//         return new ErrorResponse(e.getMessage());
//     }
// }
