# ADR-0010: 에러 추적에 Sentry를 도입하고 5xx만 수집

- 상태: 승인
- 날짜: 2026-08-02

## 맥락

운영 중 발생한 서버 에러를 확인하려면 ECS 태스크의 CloudWatch 로그를 직접 뒤져야 했다.
로그는 ECS(Elastic Common Schema) JSON으로 구조화되어 있지만 수집·알림·집계 계층이 없어,
에러가 났다는 사실 자체를 사용자 제보 전에는 알 수 없었다.

에러 추적 도구를 붙일 때 결정해야 할 것이 두 가지 있었다. 무엇을 보낼 것인가, 그리고 어떻게 보낼 것인가다.

Sentry의 Spring 통합은 `SentryExceptionResolver`를 최우선순위로 자동 등록해 `@ExceptionHandler`보다
**먼저** 모든 예외를 캡처한다. 그대로 두면 `NotFoundException`(404)·`ConflictException`(409)처럼
정상적인 클라이언트 오류까지 전부 이슈로 쌓인다. 알림이 노이즈로 가득 차면 알림을 보지 않게 되고,
그러면 도구를 도입한 의미가 없어진다.

## 결정

- **에러 추적만 도입한다.** 성능 트레이싱(`traces-sample-rate: 0`)과 로그 수집(`sentry-logback`)은 제외한다.
  로그 수집은 현재 `log.error` 사용처가 없어 얻는 것이 없고, 붙이면 같은 예외가 예외 경로와 로그 경로
  양쪽으로 들어와 중복 이슈가 생긴다
- **prod 프로파일에서만 활성화한다.** dev/local에는 `sentry` 설정 블록을 두지 않는다 —
  DSN이 없으면 SDK가 no-op으로 동작하므로 별도 on/off 플래그가 필요 없다
- **처리되지 않은 예외(5xx)만 보낸다.** `sentry.exception-resolver-order`를 최하위(`2147483647`)로 미뤄
  자동 resolver를 무력화하고, `GlobalExceptionHandler`의 최종 핸들러에서
  `Sentry.captureException`을 명시적으로 호출한다
- **DSN이 없어도 기동은 성공한다** (`${SENTRY_DSN:}`). DB 설정은 값이 없으면 기동에 실패하도록
  기본값을 두지 않았지만 Sentry는 성격이 다르다 — 모니터링 설정 하나 때문에 API 전체가
  기동 실패하는 쪽이 더 나쁘다
- **사용자 식별 정보를 수집하지 않는다** (`send-default-pii: false`). 로그인이 MVP 제외(ADR-0004)라
  식별할 주체가 없고, 요청 헤더·IP도 보내지 않는다
- 의존성은 Boot 4 전용 모듈 `io.sentry:sentry-spring-boot-4`를 쓴다.
  `sentry-spring-boot-starter-jakarta`는 Boot 3용이라 이 프로젝트에서 동작하지 않는다

## 결과

- `GlobalExceptionHandler`에 `@ExceptionHandler(Exception.class)`가 추가되면서, 지금까지 Spring 기본
  포맷(`timestamp`/`status`/`error`/`path`)으로 나가던 500 응답이 4xx와 같은 `ErrorResponse` 포맷으로
  통일된다. 내부 예외 메시지는 노출하지 않는다
- 이 핸들러는 Spring MVC 표준 예외를 담당하는 `DefaultHandlerExceptionResolver`보다 먼저 실행되므로,
  `org.springframework.web.ErrorResponse` 구현체를 `instanceof`로 걸러 원래 상태코드를 유지한다.
  이 분기가 없으면 405·415가 모두 500으로 바뀐다 — 회귀 테스트로 고정했고, 분기를 제거하면
  그 테스트만 실패하는 것을 확인했다
- Sentry로 실제 전송되는지는 자동 테스트로 검증하지 않는다. `Sentry.captureException`이 static이라
  래퍼 인터페이스 없이는 관측할 수 없는데, 구현체가 하나뿐인 인터페이스를 유지하는 비용 대비 얻는 것이
  적다고 판단했다. 배포 후 1회 수동 검증하고, 이후 장기간 이슈가 0건이면 설정을 의심한다
- 이미지 빌드 시 커밋 SHA가 `SENTRY_RELEASE`로 구워져 이슈마다 배포 버전이 표시된다.
  ECS 태스크 정의는 건드리지 않는 방식이다
- DSN은 SSM Parameter Store(`/focus-makers/prod/sentry/dsn`, SecureString)에 두고 태스크 정의가
  `SENTRY_DSN`으로 주입한다 — DB 시크릿과 같은 방식이다
- 서블릿 필터나 `@Scheduled`/`@Async`에서 발생하는 예외는 이 경로를 타지 않아 잡히지 않는다.
  현재 해당 사용처가 없으며(인증 필터는 AUTH-DISABLED), 도입 시 재검토한다
