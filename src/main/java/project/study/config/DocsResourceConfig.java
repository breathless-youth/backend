package project.study.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * FE용 계약 문서(classpath:/docs/*.html)를 /docs/** 로 서빙한다. static/ 밖에 두고 스위치로만 켠다 — 값이 없으면
 * 꺼짐이 기본이라 운영 이미지에는 경로 자체가 없다(404). 스위치 규칙은 BY-640, 문서는 BY-667.
 */
@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "true")
public class DocsResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docs/**").addResourceLocations("classpath:/docs/");
    }

    // ResourceHttpRequestHandler는 컨텐츠 타입을 서블릿 컨테이너의 기본 MIME 매핑("text/html", charset 없음)으로
    // 정하므로 그대로 두면 UTF-8 바이트를 클라이언트가 ISO-8859-1로 오인한다. setContentType은 charset이 없는
    // 값으로는 이미 설정된 문자 인코딩을 덮어쓰지 않으므로, 핸들러 실행 전에 먼저 지정해 둔다.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Utf8ResponseEncodingInterceptor()).addPathPatterns("/docs/**");
    }

    private static final class Utf8ResponseEncodingInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            return true;
        }
    }
}
