package project.study.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Study API").version("v1").description("""
                                공부 기록 앱 백엔드 API 문서.

                                **용어 안내**
                                - **access 토큰**: API를 호출할 때 신분 증명으로 쓰는 입장권. 발급 후 30분간 유효하다.
                                - **refresh 토큰**: 입장권이 만료됐을 때 새것으로 바꾸는 교환권. 30일 유효, 1회용.

                                **인증 흐름 (순서대로)**
                                1. 앱이 구글 로그인으로 받은 ID 토큰을 `POST /api/auth/login`에 보내면 위 토큰 두 개를 받는다.
                                2. 이후 API 호출 시 access 토큰을 `Authorization: Bearer` 헤더에 담아 보낸다.
                                   (이 문서에서는 우측 상단 **Authorize** 버튼에 access 토큰을 넣으면 된다)
                                3. access 토큰이 만료되면 `POST /api/auth/refresh`로 새 토큰 쌍을 받는다.

                                인증 관련 실패는 모두 `401` 상태코드로 내려가며,
                                본문이 있는 경우 `{"error": "사유"}` 형태로 실패 이유가 담긴다."""))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
