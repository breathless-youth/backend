package project.study.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    // Boot 4에서 RestClient 자동설정은 별도 모듈(spring-boot-restclient)로 분리되어
    // webmvc 스타터만으로는 RestClient.Builder 빈이 등록되지 않는다
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
