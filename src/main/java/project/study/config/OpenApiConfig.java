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
                                공부 기록 앱 백엔드 API.

                                **인증 흐름**: `POST /api/auth/login`에 구글 ID 토큰을 보내 access/refresh 토큰을 받는다.
                                우측 상단 Authorize 버튼에 access 토큰을 넣으면 보호된 API를 호출할 수 있다.
                                access 토큰 만료(30분) 시 `POST /api/auth/refresh`로 재발급한다(1회용, 회전).
                                인증 실패 응답은 `401 {"error": "사유"}` 형태다."""))
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
