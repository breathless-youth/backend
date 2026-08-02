# Sentry 에러 추적 도입 설계

- 작성일: 2026-08-02
- 상태: 승인됨
- 관련 ADR: ADR-0010 (이 설계와 함께 추가 예정)

## 배경

현재 운영 중 발생한 서버 에러를 확인하려면 ECS 태스크의 CloudWatch 로그를 직접 뒤져야 한다.
에러가 났다는 사실 자체를 알아채는 수단이 없어, 사용자 제보가 유일한 탐지 경로다.

로그는 이미 ECS(Elastic Common Schema) JSON 한 줄 포맷으로 구조화되어 있으나,
수집·알림·집계 계층이 없어 "언제 무엇이 몇 번 터졌는지"를 파악할 수 없다.

## 목표

처리되지 않은 서버 에러(5xx)가 발생하면 Sentry에 이슈로 쌓이고, 알림으로 즉시 인지할 수 있게 한다.

## 범위

| 항목 | 결정 |
|---|---|
| 기능 범위 | 에러 추적만 (성능 트레이싱·로그 수집 제외) |
| 적용 환경 | prod만 |
| 수집 대상 | 5xx(처리되지 않은 예외)만 |
| 릴리스 추적 | 포함 (커밋 SHA) |

### 비범위 (Non-goals)

- **성능 트레이싱** — `traces-sample-rate: 0`. API 응답시간·N+1 추적은 필요해지면 별도로 검토한다.
- **로그 수집(`sentry-logback`)** — 현재 코드에 `log.error` 사용이 0건이라 얻는 것이 없다.
  더 중요하게는, 붙이면 같은 예외가 예외 경로와 로그 경로 양쪽으로 들어와 **중복 이슈**가 생긴다.
- **dev/local 환경 수집** — 노이즈와 무료 쿼터 소진을 피한다.
- **사용자 식별(PII)** — 로그인이 MVP에서 제외된 상태(ADR-0004)라 식별할 주체가 없다.
  `send-default-pii: false`로 요청 헤더·IP도 수집하지 않는다.

## 결정과 근거

### 1. 의존성: `io.sentry:sentry-spring-boot-4:8.51.0`

```gradle
implementation 'io.sentry:sentry-spring-boot-4:8.51.0'
```

- **Spring Boot 4 전용 모듈이다.** 검색하면 대부분 `sentry-spring-boot-starter-jakarta`가 나오지만
  그것은 Boot 3용이다. Sentry는 Boot 2/3/4에 대해 각각 다른 artifact를 배포한다.
- Boot 4 BOM이 Sentry 버전을 관리하지 않으므로 **버전 명시가 필수**다 (rest-assured와 같은 상황).
- `sentry-spring-boot-4-starter`는 여기에 Boot 4 starter만 얹은 것으로, logback 통합은 포함하지 않는다.
  우리는 logback 통합을 쓰지 않으므로 하위 모듈을 직접 쓴다.
- Jackson 3 충돌 없음 — Sentry는 자체 JSON 직렬화기를 사용한다.
  (jjwt와 달리 별도 조치 불필요. 구현 시 `./gradlew dependencies`로 재확인한다.)

### 2. 설정: prod 프로파일에만

```yaml
# application-prod.yaml
sentry:
  dsn: ${SENTRY_DSN:}
  environment: prod
  release: ${SENTRY_RELEASE:unknown}
  send-default-pii: false
  traces-sample-rate: 0
  exception-resolver-order: 2147483647
```

`dev`/`local`에는 `sentry` 블록을 두지 않는다. DSN이 없으면 SDK가 no-op으로 동작하므로,
별도 on/off 플래그 없이 "prod만"이 성립한다.

**`${SENTRY_DSN:}`의 빈 기본값은 의도적이다.** DB 설정은 값이 없으면 기동에 실패하도록
기본값을 두지 않았지만(설정 누락을 늦게 발견하지 않기 위함), Sentry는 성격이 다르다.
DB가 없으면 앱이 무용지물이지만 Sentry가 없어도 앱은 정상 동작한다.
**모니터링 설정 하나 때문에 API 전체가 기동 실패하는 쪽이 더 나쁘다.**
DSN이 비면 Sentry SDK가 기동 로그에 비활성 메시지를 남기므로 누락은 발견할 수 있다.

**`exception-resolver-order`를 최하위로 미루는 것이 중복 방지의 핵심이다.**
Sentry의 `SentryExceptionResolver`는 기본적으로 최우선순위(`Integer.MIN_VALUE`)로 등록되어
`@ExceptionHandler`보다 **먼저** 모든 예외를 캡처한다. 그대로 두면 404·409 같은
정상적인 클라이언트 오류까지 이슈로 쌓이고, 아래 3번의 명시적 캡처와도 겹쳐 같은 에러가 두 번 올라간다.
순서를 최하위로 미루면 `@ExceptionHandler`가 처리한 예외는 resolver까지 도달하지 않는다.

### 3. 캡처 방식: 명시적 호출

`GlobalExceptionHandler`의 최종 핸들러에서 `Sentry.captureException`을 직접 호출한다.
별도 추상화 계층을 두지 않는다.

**`project.study.common.GlobalExceptionHandler`** — 최종 핸들러 추가

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
    // 405·415 같은 Spring MVC 표준 예외까지 500으로 둔갑시키지 않는다
    if (e instanceof org.springframework.web.ErrorResponse standard) {
        return ResponseEntity.status(standard.getStatusCode())
                .body(new ErrorResponse("요청을 처리할 수 없습니다"));
    }
    Sentry.captureException(e);
    return ResponseEntity.internalServerError()
            .body(new ErrorResponse("서버 오류가 발생했습니다"));
}
```

`Sentry.captureException`은 static 메서드다. 주입 가능한 래퍼 인터페이스를 두면
전송 여부를 단위테스트로 검증할 수 있지만, 구현체가 하나뿐인 인터페이스를 유지하는 비용 대비
얻는 것이 적다고 판단해 **전송 여부의 자동 검증은 포기한다.**
핸들러가 클래스 하나에 닫혀 있어 호출 지점이 눈으로 추적 가능하다는 점이 근거다.
상태코드·응답 포맷 검증은 이 결정과 무관하게 그대로 유지한다.

**`instanceof` 분기가 없으면 회귀가 발생한다.** `@ExceptionHandler(Exception.class)`는
`ExceptionHandlerExceptionResolver`에서 처리되는데, 이는 Spring MVC 표준 예외를 담당하는
`DefaultHandlerExceptionResolver`보다 우선순위가 높다. 따라서 분기가 없으면
`HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotSupportedException`(415) 등이
모두 500으로 바뀐다. `org.springframework.web.ErrorResponse`는 이런 표준 예외들이 구현하는
인터페이스이므로, `instanceof`로 걸러 원래 상태코드를 유지한다.

인터페이스 이름이 기존 `project.study.common.ErrorResponse` record와 겹치므로
**코드에서는 FQCN으로 명시**해 혼동을 막는다.

부수 효과로, 지금까지 Spring 기본 포맷(`timestamp`/`status`/`error`/`path`)으로 나가던
500 응답이 4xx와 동일한 `ErrorResponse` 포맷으로 통일된다. 내부 예외 메시지는 노출하지 않는다.

### 4. 릴리스 추적

이미 커밋 SHA로 이미지를 태깅하고 있으므로 그 값을 이미지에 굽는다.
ECS 태스크 정의를 건드리지 않아도 되는 방식이다.

```dockerfile
# Dockerfile (2단계: 실행)
ARG GIT_SHA=unknown
ENV SENTRY_RELEASE=$GIT_SHA
```

```yaml
# .github/workflows/deploy.yml
docker build --provenance=false --sbom=false \
  --build-arg GIT_SHA=${{ github.sha }} \
  -t $IMAGE:${{ github.sha }} -t $IMAGE:latest .
```

이슈마다 "어느 배포에서 터졌는지"가 표시된다.
`ARG`는 사용되는 빌드 스테이지마다 선언해야 하므로 실행 스테이지에 둔다.

Sentry에 릴리스 객체를 **생성**하고 커밋 목록을 연결하는 것(sentry-cli + auth token)은
이번 범위에 포함하지 않는다. 릴리스 태그만으로도 배포 구분은 충분하다.

## 테스트

`GlobalExceptionHandlerTest` (단위):

| 입력 | 기대 응답 |
|---|---|
| `RuntimeException` (예상 못 한 예외) | 500 + `ErrorResponse` 포맷, 내부 메시지 미노출 |
| `NotFoundException` | 404 |
| `BadRequestException` | 400 |
| `ConflictException` | 409 |
| `HttpRequestMethodNotSupportedException` | **405 유지** |

마지막 행이 3번에서 설명한 회귀(표준 MVC 예외가 500으로 둔갑)를 막는 회귀 테스트로,
이번 변경에서 가장 중요한 테스트다.

통합 테스트로는 존재하지 않는 HTTP 메서드 호출이 405를 유지하는지 확인한다
(`MockMvcTester` 사용, 기존 API 통합테스트 관례를 따른다).

### 자동 검증하지 않는 것

**"실제로 Sentry에 이벤트가 도달했는가"는 테스트하지 않는다** (3번 결정에 따름).
대신 배포 후 1회 수동으로 확인한다: 로컬에서 발급받은 DSN을 넣고 애플리케이션을 띄운 뒤
의도적으로 예외를 발생시켜 Sentry 콘솔에 이슈가 생기는지 본다.
운영 코드에 예외를 유발하는 테스트용 엔드포인트를 남기지 않는다.

## 인프라 (완료됨)

| 항목 | 상태 |
|---|---|
| SSM 파라미터 `/focus-makers/prod/sentry/dsn` | SecureString, Version 1 — 생성 완료 |
| ECS 태스크 정의 `focus-makers-prod-api` | **rev 3** 등록 완료 (rev 2 + `SENTRY_DSN` secret) |
| execution role SSM 권한 | 기존 `/focus-makers/prod/*` 와일드카드로 커버 — 변경 없음 |

서비스는 rev 2로 실행 중이며 강제 재배포하지 않았다.
`deploy.yml`이 배포마다 최신 태스크 정의를 가져와 이미지만 교체하므로,
다음 `main` 배포에서 rev 3이 자연스럽게 적용된다.

## 문서

- `docs/adr/0010-sentry-error-tracking.md` — 왜 5xx만 수집하는지, 왜 명시적 캡처인지
- `CLAUDE.md` 기술 스택 주의사항에 추가:
  Sentry는 Boot 4용 `sentry-spring-boot-4` 사용 (`sentry-spring-boot-starter-jakarta`는 Boot 3용)

## 리스크

| 리스크 | 대응 |
|---|---|
| 무료 쿼터(5만건/월) 소진 | 5xx만 수집하므로 정상 상황에서는 거의 발생하지 않는다. 장애가 나면 같은 이슈로 묶여 집계된다. |
| 500 응답 포맷 변경이 클라이언트에 영향 | 500은 원래 계약된 응답이 아니며, 4xx와 포맷이 통일되어 파싱은 오히려 단순해진다. |
| MVC 밖(서블릿 필터) 예외를 놓침 | 현재 커스텀 필터가 없다(인증 필터는 AUTH-DISABLED). 인증 재도입 시 재검토한다. |
| `@Scheduled`/`@Async` 예외를 놓침 | 현재 사용처가 없다. 도입 시 해당 실행기에 캡처를 추가한다. |
| Sentry 전송이 조용히 깨져도 자동으로 알 수 없음 | 래퍼 추상화를 두지 않기로 한 결정의 대가. 배포 후 1회 수동 검증하고, 이후 장기간 이슈가 0건이면 설정을 의심한다. |

## 후속 작업 (별도 처리)

- 노출된 Sentry auth token 회전 (릴리스 스코프, 대화 중 평문 노출됨)
- Sentry 콘솔에서 알림 채널 연동 (배포 알림용 Slack 워크스페이스 재사용 가능)
- 필요 시 sentry-cli로 릴리스-커밋 연결 (GitHub Secrets에 auth token 등록)
