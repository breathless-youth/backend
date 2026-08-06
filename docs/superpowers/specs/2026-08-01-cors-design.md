# CORS 설정 설계

- 날짜: 2026-08-01
- 대상: 웹 클라이언트 `https://web.sunqstudio.kr`

## 배경

웹 클라이언트가 브라우저에서 백엔드 API를 직접 호출한다. 현재 CORS 설정이 전혀 없어
모든 교차 출처 요청이 브라우저에 의해 차단된다.

제약 조건이 하나 있다. 로그인이 MVP에서 제외되면서(ADR-0004) `spring-boot-starter-security`
의존성이 주석 처리되어 **Spring Security가 클래스패스에 없다.** 따라서 통상적인
`http.cors()` DSL을 쓸 수 없고 Spring MVC 레벨에서 설정해야 한다.

## 결정

`WebMvcConfigurer.addCorsMappings()`로 한 곳에서 설정한다.
컨트롤러마다 `@CrossOrigin`을 붙이는 방식은 새 컨트롤러에서 누락되기 쉬워 제외했다.

| 항목 | 값 | 근거 |
|---|---|---|
| 경로 | `/api/**` | actuator는 브라우저가 호출하지 않는다 |
| Origin | 프로파일별 `app.cors.allowed-origins` | 환경마다 다르다 |
| ├ prod | `https://web.sunqstudio.kr` | |
| └ local·dev | 위 + `http://localhost:[*]`, `http://127.0.0.1:[*]` | 프론트 로컬 개발 |
| 메서드 | GET, POST, PUT, PATCH, DELETE, OPTIONS | 현재는 GET·POST만 쓰지만 엔드포인트 추가 시마다 고치지 않도록 |
| 헤더 | `*` | credentials 불허라 안전하다 |
| credentials | `false` | 인증 비활성(ADR-0004). 쿠키를 주고받지 않는다 |
| maxAge | 3600초 | preflight 재요청을 1시간 캐시 |

### `allowedOrigins`가 아니라 `allowedOriginPatterns`

프론트 로컬 개발 서버 포트는 번들러에 따라 다르다(Vite 5173, Next 3000 등).
패턴을 쓰면 `http://localhost:[*]` 한 줄로 포트를 고정하지 않을 수 있다.
`:[*]`가 Spring `CorsConfiguration`의 포트 와일드카드 문법이다 — `:*`도 호스트 패턴의
일반 와일드카드로 처리되어 우연히 동작하지만 지원되는 문법이 아니다.
prod에는 와일드카드 없이 정확한 도메인만 적으므로 정확 매칭과 동일하게 동작한다.

### origin을 코드가 아니라 프로퍼티로

도메인이 바뀌어도 ECS 태스크 정의에 환경변수를 넣어 이미지 재빌드 없이 덮어쓸 수 있다.
이름은 `APP_CORS_ALLOWEDORIGINS_0` 형태다 — relaxed binding은 하이픈을 밑줄로 바꾸는 게
아니라 **제거**하므로 `APP_CORS_ALLOWED_ORIGINS`로는 바인딩되지 않는다.
값은 기동 시점에 한 번 바인딩되므로 바꾸려면 태스크를 새로 띄워야 한다.

### 설정이 비면 매핑을 등록하지 않는다

`allowedOrigins`가 비어 있으면 `addCorsMappings`가 아무것도 등록하지 않는다.
설정 누락이 "모든 origin 허용"으로 이어지지 않게 하는 안전한 기본값이다.

## 구성 요소

- `config/CorsProperties` — `@ConfigurationProperties("app.cors")` record.
  compact 생성자에서 null을 빈 리스트로 정규화해 프로파일에 설정이 없어도 NPE가 나지 않는다.
- `config/WebConfig` — `WebMvcConfigurer` 구현체. `CorsProperties`를 생성자 주입받는다.
- `application-{local,dev,prod}.yaml` — origin 목록. `application-local.yaml`은 gitignore
  대상이므로 `.example`도 함께 갱신한다.

## 보안 영향

브라우저가 `web.sunqstudio.kr`에서 API를 호출할 수 있게 여는 변경이다.
인증이 비활성인 현재로선 공개 API의 origin을 명시적으로 제한하는 수준의 의미를 갖는다.

credentials 불허가 보장하는 범위는 정확히 말해 **응답 차단**이다. 제3자 사이트가 보낸
교차 출처 요청 중 단순 요청(GET, 폼 형식 POST)은 서버까지 도달하고 처리된다 —
브라우저가 막는 것은 그 응답을 스크립트가 읽는 것뿐이다. 따라서 CORS는 상태 변경 요청에
대한 방어 수단이 아니다. 인증을 재도입할 때는 CORS와 별개로 SameSite 쿠키 정책과
CSRF 방어를 함께 판단해야 한다.

**인증 재도입 시 재검토가 필요하다.** 설정을 Security 필터체인의 `http.cors()`로 옮기고
credentials 정책을 다시 판단해야 한다. 해당 내용은 `WebConfig`에 `AUTH-DISABLED` 주석으로 남긴다.

## 테스트

`CorsIntegrationTest` (MockMvcTester 통합테스트)

1. 허용 origin의 preflight(OPTIONS)에 `Access-Control-Allow-Origin`이 실린다
2. 허용되지 않은 origin의 preflight는 거부된다
3. localhost 패턴은 포트와 무관하게 허용된다
4. 실제 GET 요청 응답에도 CORS 헤더가 붙는다
5. 인증 재도입에 대비해 `Authorization` 헤더가 preflight에서 허용된다
6. `Access-Control-Allow-Credentials`가 응답에 없다
