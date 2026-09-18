package project.study.config;

import jakarta.servlet.DispatcherType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ErrorResponse;
import project.study.user.jwt.JwtFilter;
import project.study.user.jwt.JwtUtil;
import tools.jackson.databind.ObjectMapper;

/**
 * 비회원(DEVICE) 토큰 인증 (ADR-0019). 보호 API는 {@code Authorization: Bearer <access>}가 필수이고,
 * 없거나 무효면 401 JSON({@code code: UNAUTHORIZED})으로 답한다. 소셜 로그인은 파킹 상태다.
 */
@EnableWebSecurity
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final long PREFLIGHT_CACHE_SECONDS = 3600;

    private final CorsProperties corsProperties;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        // 에러 디스패치(/error)까지 인가하면 미인증 요청의 404·500이 401로 뒤집힌다
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        // 토큰이 시작되는 두 경로. 메서드를 한정한다 — /api/users/** 를 열면 /me/profile이 뚫린다
                        .requestMatchers(HttpMethod.POST, "/api/users")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh")
                        .permitAll()
                        // WebSocket 핸드셰이크 — 인증은 CONNECT 프레임에서 한다 (WebSocketConfig)
                        .requestMatchers("/ws")
                        .permitAll()
                        // ALB 헬스체크. metrics·wsstats 등 나머지 actuator는 토큰이 있어야 본다
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // API 문서 — prod는 springdoc 자체가 꺼져 있어 404
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                        .permitAll()
                        // FE용 계약 문서(웹소켓 명세) — prod는 app.docs.enabled가 없어 매핑 자체가 없다(404) (BY-667)
                        .requestMatchers("/docs/**")
                        .permitAll()
                        // 구 앱(v1.2.x) 병행 — 구 앱이 쓰는 경로만, API-Version 헤더가 없거나 1일 때만 연다.
                        // 강제 업데이트 뒤 contract 시 이 줄과 LegacyApiRequestMatcher를 삭제한다 (ADR-0020)
                        .requestMatchers(new LegacyApiRequestMatcher())
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedEntryPoint()))
                // Bearer가 있으면 principal(Long userId)을 세운다
                .addFilterBefore(new JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = corsProperties.allowedOrigins();
        if (!origins.isEmpty()) {
            config.setAllowedOriginPatterns(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);
        config.setMaxAge(PREFLIGHT_CACHE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // 다른 에러와 같은 {code, message} 모양으로 — 클라이언트는 code로 refresh 재시도 여부를 가른다
    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), new ErrorResponse(ErrorCode.UNAUTHORIZED, "인증이 필요합니다"));
        };
    }
}
