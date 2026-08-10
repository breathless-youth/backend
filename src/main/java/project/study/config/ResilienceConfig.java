package project.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * 메서드 레벨 {@code @Retryable}(Spring Framework 7 코어, {@code org.springframework.resilience})을
 * 켠다. 이 애노테이션은 Spring 컨테이너가 만든 프록시를 통해 호출될 때만 동작한다 — {@code new}로 직접
 * 생성한 인스턴스에서 호출하면 재시도 없이 그냥 실행된다.
 */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {}
