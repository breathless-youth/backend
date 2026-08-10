package project.study.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    // DevDataSeeder가 시딩하는 내용과 함께 유지한다
    private static final String DEV_MOCK_DATA_GUIDE = """

            **목데이터 안내 (dev 전용)** — 서버 시작 시 데모 데이터가 자동 시딩된다. 아래 "서버 시작일" 기준 상대 날짜는
            실제 오늘 날짜가 아니라 서버가 마지막으로 기동된 시점의 날짜다 — 서버를 자정 넘어서까지 계속 띄워두면
            실제 오늘 날짜와 어긋날 수 있으니 재시작 시각을 기준으로 읽는다 (재시작하면 그 시점 기준으로 다시 생성됨).
            - 데모 유저: `POST /api/users` 에 deviceId `%s` 로 등록하면 userId를 얻는다 (멱등)
            - 서버 시작일: 2시간 세션 (PHONE 10분 + AWAY 10분, 집중률 83.3%%)
            - 서버 시작일 하루 전 14~17시: 3시간 세션 (DEVICE 20분 + PAUSE 10분)
            - 서버 시작일 이틀 전 20:00~21:30: 이벤트 없는 세션 (집중률 100%%)
            - 서버 시작일 사흘 전 23시~이틀 전 1시: 자정을 넘겨 두 세션으로 분할 저장 (PHONE 20분이 자정에 10분씩 걸침)
            - 서버 시작일 나흘 전 09:00~09:45: 45분 세션 (PHONE 5분)
            - 그 외 최근 20일(서버 시작일 기준): 매일 0~5개 랜덤 세션 — 세션이 아예 없는 날도 섞여있다
              (07~21시, 세션당 이벤트 0~2개 — 고정 시드라 재시작해도 같은 패턴)
            """.formatted(DevDataSeeder.DEMO_DEVICE_ID);

    private static final String BEARER_AUTH = "bearerAuth";

    private final Environment environment;

    @Bean
    public OpenAPI openApi() {
        String description = """
                공부 기록 앱 백엔드 API 문서.

                **인증 방법** — 소셜 로그인(`POST /api/auth/login`)으로 access 토큰을 발급받은 뒤, \
                모든 API 요청에 `Authorization: Bearer <access_token>` 헤더를 붙인다. \
                아래 자물쇠 버튼으로 토큰을 입력하면 Swagger UI에서도 인증된 요청을 보낼 수 있다.
                """;
        if (environment.matchesProfiles("dev")) {
            description += DEV_MOCK_DATA_GUIDE;
        }
        return new OpenAPI()
                .info(new Info().title("Study API").version("v1").description(description))
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
