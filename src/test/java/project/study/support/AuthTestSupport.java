package project.study.support;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import project.study.config.ApiVersionConfig;

/** API 테스트용 인증 주입 — 실제 JWT 왕복은 AuthApiIntegrationTest 한 곳에서만 검증한다. */
public final class AuthTestSupport {

    /** 토큰 계약의 버전 — 구 앱 대응이 있는 경로에 새 앱이 붙이는 값 (ADR-0020). 새 경로는 기본버전 1이다 (ADR-0015 갱신). */
    public static final String TOKEN_API_VERSION = "2";

    private AuthTestSupport() {}

    /**
     * JwtFilter가 넣는 것과 같은 모양의 Authentication을 요청에 싣고, {@code API-Version: 2}도 함께 싣는다 —
     * 보호 API 대부분이 토큰 계약(v2)이라서다. 기본버전(1) 경로(과목·study-days)나 미지원 버전 검증처럼 테스트가 헤더를
     * 직접 지정했으면 건드리지 않는다.
     *
     * <p>principal은 반드시 {@code Long}이어야 한다 — String이면 {@code @AuthenticationPrincipal Long}이
     * 조용히 null로 풀린다. 3-인자 생성자여야 {@code isAuthenticated()}가 true다.
     */
    public static RequestPostProcessor asUser(long userId) {
        RequestPostProcessor authentication =
                SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
                        Long.valueOf(userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        return request -> {
            if (request.getHeader(ApiVersionConfig.HEADER) == null) {
                request.addHeader(ApiVersionConfig.HEADER, TOKEN_API_VERSION);
            }
            return authentication.postProcessRequest(request);
        };
    }
}
