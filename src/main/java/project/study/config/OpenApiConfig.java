package project.study.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    // DevDataSeeder가 시딩하는 내용과 함께 유지한다
    /** 문서가 싣는 계약의 버전 — 구 앱(v1)은 문서에 없다 (ADR-0020). */
    private static final String TOKEN_API_VERSION = "2";

    private static final String DEV_MOCK_DATA_GUIDE = """

            **목데이터 안내 (시딩이 켜진 환경 전용)** — 서버 시작 시 데모 데이터가 자동 시딩된다. 아래 "서버 시작일" 기준 상대 날짜는
            실제 오늘 날짜가 아니라 서버가 마지막으로 기동된 시점의 날짜다 — 서버를 자정 넘어서까지 계속 띄워두면
            실제 오늘 날짜와 어긋날 수 있으니 재시작 시각을 기준으로 읽는다 (재시작하면 그 시점 기준으로 다시 생성됨).
            - 데모 유저: `POST /api/users` 에 deviceId `%s` 로 등록하면 토큰 쌍을 얻는다 (멱등). userId는 accessToken의 `sub`
            - 서버 시작일: 2시간 세션 (PHONE 10분 + AWAY 10분 + SLEEP 10분, 집중률 75.0%%)
            - 서버 시작일 하루 전 14~17시: 3시간 세션 (DEVICE 20분 + PAUSE 10분)
            - 서버 시작일 이틀 전 20:00~21:30: 이벤트 없는 세션 (집중률 100%%)
            - 서버 시작일 사흘 전 23시~이틀 전 1시: 자정을 넘겨 두 세션으로 분할 저장 (PHONE 20분이 자정에 10분씩 걸침)
            - 서버 시작일 나흘 전 09:00~09:45: 45분 세션 (PHONE 5분)
            - 그 외 최근 20일(서버 시작일 기준): 매일 0~5개 랜덤 세션 — 세션이 아예 없는 날도 섞여있다
              (07~21시, 세션당 이벤트 0~2개 — 고정 시드라 재시작해도 같은 패턴)
            """.formatted(DevDataSeeder.DEMO_DEVICE_ID);

    // 시딩 스위치와 같은 값을 본다 — 안내는 시딩이 실제로 켜진 환경에서만 붙어야 한다 (BY-640).
    // boolean으로 받지 않는다: Spring의 문자열→boolean 변환은 "on"/"1"도 true로 보지만 시더의
    // @ConditionalOnProperty(havingValue = "true")는 "true"(대소문자 무시)만 인정해, 같은 값을 두 곳이
    // 다르게 해석하면 시딩 없이 안내만 붙는다. 시더와 같은 판정을 그대로 쓴다
    private static final String BEARER_AUTH = "bearerAuth";

    private final boolean seedEnabled;

    public OpenApiConfig(@Value("${app.seed.enabled:false}") String seedEnabled) {
        this.seedEnabled = "true".equalsIgnoreCase(seedEnabled);
    }

    /**
     * springdoc이 버전 있는 오퍼레이션마다 자동으로 싣는 {@code API-Version} 헤더 파라미터를 다듬는다 — 기본값이 Spring
     * 기본버전(1 → "1.0.0")이라 Swagger UI에서 그대로 보내면 구 앱 핸들러로 가서 400이 난다. 문서는 토큰 계약(v2)만
     * 싣으므로 기본값 2·필수로 바꾼다 (ADR-0020). 전체 문서가 만들어진 뒤 도는 customizer라 파라미터 추가 시점과 무관하다.
     */
    @Bean
    public OpenApiCustomizer apiVersionHeaderParameterCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            openApi.getPaths().values().stream()
                    .flatMap(path -> path.readOperations().stream())
                    .filter(operation -> operation.getParameters() != null)
                    .flatMap(operation -> operation.getParameters().stream())
                    .filter(parameter -> ApiVersionConfig.HEADER.equals(parameter.getName()))
                    .forEach(parameter -> {
                        parameter.setRequired(true);
                        parameter.setDescription("토큰 계약 버전 — 새 앱은 항상 `2`. 없거나 `1`이면 구 앱(v1.2.x) 계약으로 해석된다");
                        if (parameter.getSchema() != null) {
                            parameter.getSchema().setDefault(TOKEN_API_VERSION);
                        }
                    });
        };
    }

    @Bean
    public OpenAPI openApi() {
        String description = """
                공부 기록 앱 백엔드 API 문서.

                **인증** — `POST /api/users`로 기기(UUID)를 등록해 access·refresh 토큰 쌍을 받고, \
                이후 모든 API 호출에 `Authorization: Bearer <accessToken>` 헤더를 붙인다 (ADR-0019). \
                요청에 userId를 실을 필요는 없다 — 서버가 토큰에서 신원을 읽는다. \
                access가 만료되면(401 `UNAUTHORIZED`) `POST /api/auth/refresh`로 재발급받는다. \
                아래 자물쇠 버튼에 access 토큰을 넣으면 Swagger UI에서도 인증된 요청을 보낼 수 있다.

                **API-Version 헤더** — 새 앱은 **모든 HTTP 요청에 `API-Version: 2`** 를 붙인다 (ADR-0015·0020). \
                헤더가 없거나 `1`이면 스토어의 구 앱(v1.2.x) 계약(토큰 대신 요청의 userId)으로 해석된다 — \
                토큰만 있고 헤더가 빠지면 구 앱 핸들러로 가서 400이다. `2`인데 토큰이 없거나 만료면 401. \
                미지원·파싱 불가 값은 400. 이 문서는 토큰 계약(v2)만 싣는다 — 구 앱 계약은 v1.2.1과 같고 ADR-0020에 표로 있으며 \
                강제 업데이트 뒤 삭제된다. 아래 각 오퍼레이션의 `API-Version` 헤더 파라미터에는 `2`를 넣는다.
                """;
        if (seedEnabled) {
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
                // 전역 요구 — 인증 없이 부르는 등록·refresh는 @SecurityRequirements로 개별 해제한다
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
