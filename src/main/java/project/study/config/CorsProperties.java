package project.study.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 교차 출처 요청을 허용할 origin 목록. 프로파일별 yaml에서 주입받는다.
 *
 * 환경변수로 덮어쓸 때는 relaxed binding 규칙상 하이픈이 제거된 이름을 쓴다 —
 * APP_CORS_ALLOWEDORIGINS_0, APP_CORS_ALLOWEDORIGINS_1 (또는 콤마로 이어붙인 APP_CORS_ALLOWEDORIGINS).
 * 바인딩은 기동 시점에 한 번이므로 값을 바꾸면 태스크를 새로 띄워야 한다 (이미지 재빌드는 불필요).
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    // 프로파일에 설정이 없으면 null이 들어온다 — 빈 리스트로 정규화해 호출부가 null을 신경쓰지 않게 한다
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
