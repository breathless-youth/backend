package project.study.user.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ErrorCode;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.UserRegisterRequest;

/**
 * 브라우저용 refresh·기기 쿠키 (ADR-0050)
 *
 * PC 브라우저에는 기기 보안 저장소가 없어, 요청에 {@code Refresh-Transport: cookie}가 있을 때만 refresh와 deviceId를 스크립트가 못 읽는 쿠키로 주고받는다.
 * 헤더가 없는 앱 요청은 예전처럼 본문으로만 오간다.
 */
@Component
class RefreshTokenCookies {

    static final String TRANSPORT_HEADER = "Refresh-Transport";
    static final String TRANSPORT_HEADER_DOC =
            "`cookie`를 보내면 refresh 토큰을 응답 본문 대신 HttpOnly 쿠키 `__Host-refresh_token`으로 주고받는다. "
                    + "브라우저 전용이고 앱은 보내지 않는다. 헤더가 없으면 refresh는 본문으로만 오간다";
    private static final String COOKIE_NAME = "__Host-refresh_token";
    private static final String DEVICE_COOKIE_NAME = "__Host-device_id";
    // Chrome은 쿠키 수명을 400일로 잘라 받으므로 그보다 길게 줘도 소용없다
    private static final Duration DEVICE_MAX_AGE = Duration.ofDays(400);
    private static final Pattern DEVICE_ID = Pattern.compile(UserRegisterRequest.DEVICE_ID_PATTERN);
    private static final String COOKIE_MODE = "cookie";

    private final Validator validator;
    private final Duration maxAge;

    RefreshTokenCookies(Validator validator, @Value("${refresh-token.expiration}") long refreshExpirationMs) {
        this.validator = validator;
        this.maxAge = Duration.ofMillis(refreshExpirationMs);
    }

    // 값이 정확히 하나일 때만 쿠키 모드로 본다. 프록시가 여러 값을 합치거나 변형하면 앱 모드로 남아 쿠키를 읽지 않는다.
    boolean requested(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(TRANSPORT_HEADER));
        return values.size() == 1 && COOKIE_MODE.equalsIgnoreCase(values.getFirst());
    }

    /**
     * 요청에서 쓸 refresh 토큰
     *
     * 본문에 값이 있으면 본문을 쓰고, 없을 때는 쿠키 모드 요청에서만 쿠키를 읽는다.
     * 헤더 없이 쿠키를 읽지 않는 것은 CSRF 방어다.
     * CSRF 보호를 꺼 뒀고, 같은 사이트의 다른 페이지가 보낸 요청에도 브라우저는 쿠키를 싣는다.
     * 고른 값은 본문 검증과 같은 검증기로 검사해, 앱이 받던 400 메시지를 요청 언어까지 똑같이 유지한다.
     */
    String resolve(RefreshRequest body, HttpServletRequest request) {
        String token = body != null ? body.refreshToken() : null;
        if (!StringUtils.hasText(token) && requested(request)) {
            token = singleCookieValue(request, COOKIE_NAME);
        }
        Set<ConstraintViolation<RefreshRequest>> violations = validator.validate(new RefreshRequest(token));
        if (!violations.isEmpty()) {
            ConstraintViolation<RefreshRequest> violation =
                    violations.iterator().next();
            throw new BadRequestException(
                    ErrorCode.VALIDATION_FAILED, violation.getPropertyPath() + ": " + violation.getMessage());
        }
        return token;
    }

    /**
     * 같은 이름의 쿠키가 하나뿐일 때의 값
     *
     * 같은 이름이 둘 이상이면 어느 쪽이 진짜인지 알 수 없어 하나도 고르지 않는다.
     * 그러면 토큰이 없는 요청과 같은 400이 나간다.
     */
    private static String singleCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        List<String> values = Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .toList();
        return values.size() == 1 ? values.getFirst() : null;
    }

    /**
     * 응답 본문에 실을 refresh 토큰
     *
     * 쿠키 모드면 refresh를 쿠키로 보내고 null을 돌려줘 본문에서 빠지게 한다.
     * 앱 요청이면 받은 값을 그대로 돌려준다.
     */
    String deliver(String refreshToken, HttpServletRequest request, HttpServletResponse response) {
        if (!requested(request)) {
            return refreshToken;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(COOKIE_NAME, refreshToken, maxAge));
        return null;
    }

    /** 쿠키 모드 요청이면 브라우저에 남은 refresh 쿠키를 만료시킨다. */
    void expire(HttpServletRequest request, HttpServletResponse response) {
        if (requested(request)) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(COOKIE_NAME, "", Duration.ZERO));
        }
    }

    /**
     * 쿠키 모드 요청의 기기 쿠키에 든 deviceId(소문자)
     *
     * 앱이 UUID를 기기 저장소에 두듯 브라우저는 서버가 심은 이 쿠키에 둔다. 스크립트는 읽지 못한다.
     * 쿠키 모드가 아니거나, 쿠키가 없거나 둘 이상이거나, UUID 형식이 아니면 null이다.
     */
    String deviceId(HttpServletRequest request) {
        if (!requested(request)) {
            return null;
        }
        String value = singleCookieValue(request, DEVICE_COOKIE_NAME);
        return value != null && DEVICE_ID.matcher(value).matches() ? value.toLowerCase(Locale.ROOT) : null;
    }

    /** 쿠키 모드 요청이면 기기 쿠키를 이 deviceId로 다시 심어 수명을 400일로 늘린다. deviceId가 null이면 아무것도 하지 않는다. */
    void keepDevice(String deviceId, HttpServletRequest request, HttpServletResponse response) {
        if (deviceId != null && requested(request)) {
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    cookie(DEVICE_COOKIE_NAME, deviceId.toLowerCase(Locale.ROOT), DEVICE_MAX_AGE));
        }
    }

    // __Host- 접두사가 붙은 쿠키는 Secure, Path=/, Domain 없음이 모두 맞아야 브라우저가 받는다.
    // 그래서 형제 하위 도메인이 Domain=상위 도메인으로 같은 이름의 쿠키를 심어 자기 계정으로 로그인시키는 공격을 막는다.
    // Path=/라 이 호스트의 모든 API 요청에 실리지만, 값은 인증 경로에서 Refresh-Transport 헤더가 있을 때만 읽고 스크립트는 HttpOnly라 못 읽는다.
    // Strict라 다른 사이트에서 시작된 요청에는 실리지 않는다.
    private static String cookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
