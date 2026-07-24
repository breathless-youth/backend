package project.study.user.jwt;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import io.jsonwebtoken.JwtException;
// import jakarta.servlet.FilterChain;
// import jakarta.servlet.ServletException;
// import jakarta.servlet.http.HttpServletRequest;
// import jakarta.servlet.http.HttpServletResponse;
// import java.io.IOException;
// import java.util.List;
// import lombok.RequiredArgsConstructor;
// import org.springframework.http.HttpHeaders;
// import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// import org.springframework.security.core.authority.SimpleGrantedAuthority;
// import org.springframework.security.core.context.SecurityContextHolder;
// import org.springframework.web.filter.OncePerRequestFilter;
//
// @RequiredArgsConstructor
// public class JwtFilter extends OncePerRequestFilter {
//
//     private static final String BEARER_PREFIX = "Bearer ";
//
//     private final JwtUtil jwtUtil;
//
//     @Override
//     protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain
// filterChain)
//             throws ServletException, IOException {
//
//         String header = request.getHeader(HttpHeaders.AUTHORIZATION);
//         if (header == null || !header.startsWith(BEARER_PREFIX)) {
//             filterChain.doFilter(request, response);
//             return;
//         }
//
//         String token = header.substring(BEARER_PREFIX.length());
//         try {
//             // refresh 토큰(opaque UUID)은 JWT 파싱 자체가 실패하므로 API 접근에 쓸 수 없다
//             Long userId = Long.parseLong(jwtUtil.getUserId(token));
//             UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
//                     userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
//             SecurityContextHolder.getContext().setAuthentication(authentication);
//         } catch (JwtException | IllegalArgumentException e) {
//             // 유효하지 않은 토큰은 미인증 상태로 진행하고, 401 응답은 entry point가 담당한다
//         }
//
//         filterChain.doFilter(request, response);
//     }
// }
