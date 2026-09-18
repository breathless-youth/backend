package project.study.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 구 앱(v1.2.x) 병행 기간의 인가 예외 (ADR-0020). 구 앱은 토큰도 {@code API-Version} 헤더도 못 보내므로
 * 구 앱이 실제로 호출하는 경로만, 헤더가 없거나 1일 때만 인증 없이 통과시킨다.
 *
 * <p>같은 경로라도 {@code API-Version: 2}면 이 매처에 걸리지 않아 토큰이 필요하다 — 새 앱의 access가 만료됐을 때
 * 401 대신 principal 없는 핸들러로 흘러 500이 나는 것을 막는다. 경로 목록은 v1.2.1 계약과 같아야 하고,
 * 프로필은 숫자 userId만 연다({@code /me/profile}이 헤더 없이 뚫리지 않도록).
 *
 * <p>강제 업데이트(BY-531) 뒤 contract 시 이 클래스와 {@link SecurityConfig}의 한 줄을 함께 삭제한다.
 */
final class LegacyApiRequestMatcher implements RequestMatcher {

    private static final RequestMatcher LEGACY_PATHS = new OrRequestMatcher(List.of(
            path(HttpMethod.GET, "/api/users/{userId:\\d+}/profile"),
            path(HttpMethod.PATCH, "/api/users/{userId:\\d+}/profile"),
            path(HttpMethod.POST, "/api/rooms"),
            path(HttpMethod.POST, "/api/rooms/join"),
            path(HttpMethod.POST, "/api/rooms/{roomId:\\d+}/leave"),
            path(HttpMethod.POST, "/api/study-sessions"),
            path(HttpMethod.GET, "/api/study-sessions/{id:\\d+}"),
            path(HttpMethod.PUT, "/api/study-sessions/active"),
            path(HttpMethod.GET, "/api/study-sessions/active"),
            path(HttpMethod.POST, "/api/study-sessions/recovery"),
            path(HttpMethod.GET, "/api/stats"),
            path(HttpMethod.GET, "/api/stats/streak"),
            path(HttpMethod.GET, "/api/stats/period"),
            path(HttpMethod.POST, "/api/rtc-stats")));

    @Override
    public boolean matches(HttpServletRequest request) {
        return isLegacyVersion(request) && LEGACY_PATHS.matches(request);
    }

    /** 헤더가 없거나 1이면 구 앱 계약이다 — ApiVersionConfig의 기본버전과 같은 해석. */
    static boolean isLegacyVersion(HttpServletRequest request) {
        String version = request.getHeader(ApiVersionConfig.HEADER);
        return version == null || version.trim().equals(ApiVersionConfig.DEFAULT_VERSION);
    }

    private static RequestMatcher path(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }
}
