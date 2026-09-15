package project.study.support;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** API 테스트용 인증 주입 — 실제 JWT 왕복은 AuthApiIntegrationTest 한 곳에서만 검증한다. */
public final class AuthTestSupport {

    private AuthTestSupport() {}

    /**
     * JwtFilter가 넣는 것과 같은 모양의 Authentication을 요청에 싣는다.
     *
     * <p>principal은 반드시 {@code Long}이어야 한다 — String이면 {@code @AuthenticationPrincipal Long}이
     * 조용히 null로 풀린다. 3-인자 생성자여야 {@code isAuthenticated()}가 true다.
     */
    public static RequestPostProcessor asUser(long userId) {
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
                Long.valueOf(userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
