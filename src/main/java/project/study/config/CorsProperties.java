package project.study.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 교차 출처 요청을 허용할 origin 목록. 프로파일별 yaml에서 주입받는다.
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    // 프로파일에 설정이 없으면 null이 들어온다 — 빈 리스트로 정규화해 호출부가 null을 신경쓰지 않게 한다
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
