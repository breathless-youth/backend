package project.study.config;
// AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 이 파일 전체 주석 해제
//
// import jakarta.servlet.DispatcherType;
// import lombok.RequiredArgsConstructor;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.http.MediaType;
// import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
// import org.springframework.security.config.http.SessionCreationPolicy;
// import org.springframework.security.web.AuthenticationEntryPoint;
// import org.springframework.security.web.SecurityFilterChain;
// import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
// import project.study.user.jwt.JwtFilter;
// import project.study.user.jwt.JwtUtil;
//
// @EnableWebSecurity
// @Configuration
// @RequiredArgsConstructor
// public class SecurityConfig {
//
//     private final JwtUtil jwtUtil;
//
//     @Bean
//     public SecurityFilterChain securityFilterChain(HttpSecurity http) {
//         // 앱 클라이언트 + JWT 기반 무상태 API: 세션/쿠키를 쓰지 않으므로 CSRF 공격면이 없고,
//         // 폼 로그인·httpBasic·서버 세션은 사용하지 않는다
//         return http.csrf(AbstractHttpConfigurer::disable)
//                 .formLogin(AbstractHttpConfigurer::disable)
//                 .httpBasic(AbstractHttpConfigurer::disable)
//                 .logout(AbstractHttpConfigurer::disable)
//                 .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                 .authorizeHttpRequests(request -> request
//                         // 400/500 등의 내부 에러 디스패치가 401로 뒤바뀌지 않도록 허용 (외부에서 직접 접근 불가)
//                         .dispatcherTypeMatchers(DispatcherType.ERROR)
//                         .permitAll()
//                         .requestMatchers("/api/auth/login", "/api/auth/refresh")
//                         .permitAll()
//                         .requestMatchers("/actuator/health")
//                         .permitAll()
//                         .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
//                         .permitAll()
//                         .anyRequest()
//                         .authenticated())
//                 .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedEntryPoint()))
//                 .addFilterBefore(new JwtFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
//                 .build();
//     }
//
//     private AuthenticationEntryPoint unauthorizedEntryPoint() {
//         // 웹용 로그인 페이지 리다이렉트 대신 앱이 처리할 수 있는 401 JSON을 내려준다
//         return (request, response, authException) -> {
//             response.setStatus(401);
//             response.setContentType(MediaType.APPLICATION_JSON_VALUE);
//             response.setCharacterEncoding("UTF-8");
//             response.getWriter().write("{\"error\": \"인증이 필요합니다\"}");
//         };
//     }
// }
