package project.study.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * AUTH-DISABLED: 로그인 MVP 제외(ADR-0004)로 Spring Security가 클래스패스에 없어 CORS를 MVC 레벨에서 설정한다. 인증 재도입 시
 * 이 설정을 SecurityConfig의 http.cors()로 옮기고, 쿠키를 쓴다면 allowCredentials 정책을 다시 판단해야 한다.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private static final long PREFLIGHT_CACHE_SECONDS = 3600;

    private final CorsProperties corsProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> allowedOrigins = corsProperties.allowedOrigins();
        // 설정 누락이 "전체 허용"으로 이어지지 않도록, 비어있으면 아무것도 열지 않는다
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                // allowedOrigins 대신 패턴을 쓴다 — 프론트 로컬 개발 서버 포트(5173/3000 등)를 고정하지 않기 위함
                .allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 쿠키를 주고받지 않는다 (JWT는 Authorization 헤더로 보내면 credentials 없이 동작한다)
                .allowCredentials(false)
                .maxAge(PREFLIGHT_CACHE_SECONDS);
    }
}
