package project.study.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    // AUTH-DISABLED: 로그인 MVP 제외 (ADR-0004) — 인증 재도입 시 아래 주석 해제
    // 필요 import (Spotless가 import 블록 내 주석을 지워 여기에 보존):
    //   io.swagger.v3.oas.models.Components
    //   io.swagger.v3.oas.models.security.SecurityRequirement
    //   io.swagger.v3.oas.models.security.SecurityScheme
    // private static final String BEARER_AUTH = "bearerAuth";

    // AUTH-DISABLED: description의 인증 흐름 안내는 현재 스테일 (인증 재도입 시까지 유지)
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("Study API").version("v1").description("""
                                공부 기록 앱 백엔드 API 문서.
                                """));
        // AUTH-DISABLED
        // .components(new Components()
        //         .addSecuritySchemes(
        //                 BEARER_AUTH,
        //                 new SecurityScheme()
        //                         .type(SecurityScheme.Type.HTTP)
        //                         .scheme("bearer")
        //                         .bearerFormat("JWT")))
        // .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
