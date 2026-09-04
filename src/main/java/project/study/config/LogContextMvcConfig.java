package project.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import project.study.common.logging.UserIdPathVariableInterceptor;

/** 로그 컨텍스트(ADR-0016) 중 MVC 계층에서만 잡을 수 있는 경로 변수 userId 적재를 등록한다. */
@Configuration
public class LogContextMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserIdPathVariableInterceptor()).addPathPatterns("/api/**");
    }
}
