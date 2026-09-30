package project.study.user.jwt;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import project.study.common.logging.LogContext;

/**
 * {@code Authorization: Bearer <access>} 헤더를 검증해 SecurityContext에 principal({@code Long userId})을 싣는다.
 *
 * <p>{@code @Component}가 아니다 — 빈으로 두면 Boot가 서블릿 필터로 한 번 더 등록해 시큐리티 체인 밖에서도
 * 실행된다. {@code SecurityConfig}가 {@code new JwtFilter(jwtUtil)}로만 체인에 끼운다.
 */
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authenticate(header.substring(BEARER_PREFIX.length()));
        }
        // 출처를 가리지 않고 SecurityContext의 principal을 MDC에 싣는다 (ADR-0016) — 테스트가
        // authentication() 포스트프로세서로 넣은 principal도 액세스 로그에 같은 모양으로 찍힌다
        putUserIdToLogContext();

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            // refresh 토큰(opaque UUID)은 JWT 파싱 자체가 실패하므로 API 접근에 쓸 수 없다
            Long userId = Long.parseLong(jwtUtil.getUserId(token));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException e) {
            // 유효하지 않은 토큰은 미인증 상태로 진행하고, 401 응답은 entry point가 담당한다
        }
    }

    private static void putUserIdToLogContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            LogContext.putUserId(userId);
        }
    }
}
