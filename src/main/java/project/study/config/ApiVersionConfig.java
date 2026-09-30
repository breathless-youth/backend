package project.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 버전닝 (ADR-0015) — 깨지는 변경 시 엔드포인트 단위로 신·구 버전을 병행하기 위한 설정.
 *
 * <p>버전은 API-Version 요청 헤더로 받는다. 헤더가 없으면 기본버전 1로 해석하므로
 * 버전 개념 없이 배포된 기존 앱·웹 클라이언트는 수정 없이 v1로 동작한다.
 */
@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {

    /** 버전 요청 헤더 이름 — 시큐리티 매처·로그 컨텍스트도 이 상수를 본다. */
    public static final String HEADER = "API-Version";

    /** 헤더가 없을 때의 버전 — 병행 기간에는 구 앱(v1.2.x) 계약이다 (ADR-0020). */
    public static final String DEFAULT_VERSION = "1";

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        // 이후 버전(2, 3…)은 매핑의 version 속성에서 자동 감지된다 (ApiVersionApiTest로 검증)
        configurer.useRequestHeader(HEADER).setDefaultVersion(DEFAULT_VERSION);
    }
}
