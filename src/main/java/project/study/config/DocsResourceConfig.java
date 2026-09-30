package project.study.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * FE용 계약 문서(classpath:/docs/*.html)를 /docs/** 로 서빙한다. static/ 밖에 두고 스위치로만 켠다 — 값이 없으면
 * 꺼짐이 기본이라 운영 이미지에는 경로 자체가 없다(404). 스위치 규칙은 BY-640, 문서는 BY-667.
 * 리포의 docs/(ADR·런북·스펙)는 클래스패스가 아니다 — processResources로 그 폴더를 리소스에 넣으면 여기로 노출되므로 넣지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.docs.enabled", havingValue = "true")
public class DocsResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docs/**").addResourceLocations("classpath:/docs/");
    }
}
