package project.study;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableJpaAuditing
public class StudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudyApplication.class, args);
    }

    // Boot 4에서 RestClient 자동설정은 별도 모듈(spring-boot-restclient)로 분리되어
    // webmvc 스타터만으로는 RestClient.Builder 빈이 등록되지 않는다
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
    // 시간 고정
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
