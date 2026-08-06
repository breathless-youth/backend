# Sentry 4xx 수집 확장 설계

- 작성일: 2026-08-06
- 상태: 승인됨
- 관련 ADR: ADR-0011 (이 설계와 함께 추가 예정, ADR-0010 부분 대체)

## 배경

ADR-0010에서는 Sentry 알림이 노이즈로 죽는 것을 막기 위해 "처리되지 않은 5xx만" Sentry로
보내기로 결정했다. `NotFoundException`(404)·`ConflictException`(409) 같은 도메인 4xx는
"정상적인 클라이언트 오류"로 분류해 의도적으로 제외했다.

이후 운영 경험이 쌓이면서, 4xx 중 일부(특히 `NotFoundException` — 존재하지 않는 리소스 조회)가
실제로는 클라이언트 로직 버그(잘못된 ID 참조, 삭제된 리소스 재요청 등)의 신호일 수 있다는
문제의식이 생겼다. 또한 이 프로젝트는 Sentry의 알림 기능을 애초에 쓰지 않는다 —
운영 알림은 CloudWatch 경보가 5xx 기준으로 전담하고 있어, ADR-0010이 걱정했던
"노이즈가 알림을 죽인다"는 리스크는 Sentry에는 해당하지 않는다. Sentry는 순수하게
이슈를 눈으로 훑어보는 대시보드 용도로만 쓰인다.

## 목표

모든 4xx(도메인 예외 + Spring MVC 표준 예외)를 5xx와 동일하게 Sentry 이슈로 수집해,
클라이언트 버그로 의심되는 패턴을 나중에 훑어볼 수 있게 한다.

## 범위

| 항목 | 결정 |
|---|---|
| 수집 대상 | 4xx + 5xx 전부 (기존: 5xx만) |
| 알림 정책 | 변경 없음 — Sentry 알림 미사용 유지, CloudWatch가 5xx 알람 전담 |
| 심각도(level) 구분 | 없음 — 4xx/5xx 모두 동일하게 캡처 (베이스라인, 필요해지면 추후 별도 검토) |
| 적용 환경 | 변경 없음 — prod만 |

### 비범위 (Non-goals)

- **Sentry 알림 설정 변경** — 알림 자체를 쓰지 않으므로 논의 대상이 아니다.
- **4xx/5xx 심각도(level) 태깅** — `beforeSend` 콜백으로 `warning`/`error`를 구분하는 방법도
  검토했으나, 지금 당장 필요하지 않아 범위에서 뺐다. 이슈 목록이 실제로 훑어보기 불편해지면
  그때 별도 ADR로 재검토한다.
- **선택적 캡처(특정 4xx만 제외)** — "전부 다" 캡처하는 것이 요구사항이라, 예외 타입별
  화이트리스트/블랙리스트는 만들지 않는다.

## 결정과 근거

### 1. `exception-resolver-order` 설정 반전

`application-prod.yaml`의 값을 삭제하지 않고 음수로 뒤집는다.

```yaml
# 변경 전
  exception-resolver-order: 2147483647
# 변경 후
  exception-resolver-order: -2147483647
```

> **정정 (최종 리뷰 결과 반영):** 이 설계의 초안은 ADR-0010을 따라 `SentryExceptionResolver`의
> 기본 order가 `Integer.MIN_VALUE`(최우선순위)라고 적었으나, **틀린 값**이다. 실제 기본값은
> **1**이고(`io.sentry.spring.boot4.SentryProperties` 생성자), Spring MVC가 등록하는 예외 리졸버
> 컴포짓(`@ExceptionHandler`를 실행하는 `ExceptionHandlerExceptionResolver`를 품은
> `HandlerExceptionResolverComposite`)은 order **0**이다
> (`WebMvcConfigurationSupport.handlerExceptionResolver`). 낮은 값이 먼저 실행되므로
> 기본값 그대로면 Spring 컴포짓이 앞선다.

`GlobalExceptionHandler`가 `@ExceptionHandler(Exception.class)`로 모든 예외를 소비하기 때문에,
`DispatcherServlet.processHandlerException`은 컴포짓에서 non-null을 받는 즉시 체인을 멈춘다.
따라서 **설정을 지우기만 하면 Sentry 리졸버는 호출조차 되지 않고 4xx는 물론 5xx도 전송이 끊긴다.**
0보다 작은 값을 줘야 Sentry가 먼저 캡처한다. `SentryExceptionResolver.resolveException`은 캡처 후
`null`을 반환하므로, 응답 생성은 그대로 뒤따르는 `@ExceptionHandler`가 담당한다.

같은 이유로 기존 값 `2147483647`도 실제로는 아무 효과가 없었다. ADR-0010에서 4xx가 걸러졌던
진짜 메커니즘은 리졸버 순서가 아니라 `GlobalExceptionHandler`가 5xx에서만 수동으로
`Sentry.captureException`을 호출한 것이었다.

### 2. `GlobalExceptionHandler`의 수동 캡처 제거

`GlobalExceptionHandler.java`의 `Sentry.captureException(e)` 호출 2곳(현재 65행, 70행)을 삭제한다.

- 위 1번(순서 반전)이 적용된 뒤에는 자동 resolver가 `@ExceptionHandler`보다 먼저 실행되어 모든
  예외를 이미 캡처하므로, 기존 5xx 수동 캡처를 남겨두면 5xx 하나당 Sentry 이슈가 2개
  (자동 1개 + 수동 1개) 생긴다.
- **1번과 2번은 한 세트다.** 순서 반전 없이 수동 캡처만 지우면 prod에서 Sentry로 아무것도
  전송되지 않는다.
- 405/415 같은 Spring MVC 표준 예외의 HTTP 상태코드를 지키는
  `instanceof org.springframework.web.ErrorResponse` 분기(60~69행)는 Sentry 캡처와 무관한
  로직이므로 그대로 둔다.

### 3. ADR 문서화

- **`docs/adr/0011-sentry-4xx-collection.md` 신규 작성** — 위 배경/결정/결과를 ADR 형식으로 기록
- **`docs/adr/0010-sentry-error-tracking.md`의 상태 갱신** — 전체를 폐기 처리하면 부정확하다.
  PII 미수집·DSN 처리·prod 전용 활성화·release 태깅 등 나머지 결정은 그대로 유효하기 때문이다.
  상단 상태 줄만 다음과 같이 바꾼다.
  ```
  - 상태: 부분 대체됨 (4xx/5xx 수집 범위는 ADR-0011로 대체)
  ```

## 예상 결과

- `NotFoundException`(404), `ConflictException`(409)/`DuplicateSessionException`(409),
  `BadRequestException`(400)/`InvalidSessionException`(400),
  `MethodArgumentNotValidException`(400), `HttpMessageNotReadableException`(400),
  Spring MVC 표준 예외(405/415 등)까지 전부 Sentry 이슈로 쌓인다.
- API 응답 자체(상태코드·바디)는 변경되지 않는다 — `@ExceptionHandler`의 리턴값은 그대로다.
  Sentry 캡처는 resolver 체인에서 부수효과로만 일어나고 응답 생성에 관여하지 않는다.
- Sentry 콘솔의 이슈 발생량이 크게 늘어난다. 알림을 쓰지 않으므로 당장 문제는 없지만,
  이슈 목록이 4xx로 뒤덮여 실제로 훑어보기 불편해지면 그때 심각도 구분(위 비범위 참고)을
  다시 검토한다.

## 테스트 계획

- `Sentry.captureException`이 static 메서드라 "실제로 Sentry에 도달하는지"는 ADR-0010 때와
  마찬가지로 자동 테스트로 검증하지 않는다. wrapper 인터페이스를 새로 만드는 비용 대비
  얻는 것이 적다고 판단한 기존 결정을 그대로 따른다.
- **다만 리졸버 *순서*는 자동 검증한다** (`SentryExceptionResolverOrderTest`, 최종 리뷰 결과 추가).
  "전송 여부는 검증 불가"라는 판단을 순서에까지 확대한 것이 이번 오류를 놓친 원인이었다.
  prod yaml의 실제 값을 읽어 Spring 컨텍스트에 적용하고, `DispatcherServlet`과 동일한 방식으로
  정렬한 리졸버 체인의 맨 앞이 `SentryExceptionResolver`인지 확인한다 (DSN 불필요).
- **회귀 테스트가 그대로 통과해야 한다** — `GlobalExceptionHandlerTest.java`의 405 유지
  테스트, 5xx 서버 오류 메시지 테스트는 Sentry 캡처와 무관하게 상태코드/응답 바디만
  검증하므로 이번 변경으로 깨지면 안 된다.
- **기존 4xx 통합테스트 전부 그대로 통과해야 한다** — `StudySessionApiTest`,
  `StudySessionIdempotencyApiTest`, `StudySessionMinDurationApiTest` 등. 응답 자체를
  바꾸지 않으므로 전부 그린이어야 정상이다.
- **새로 추가하는 자동 테스트는 위 리졸버 순서 테스트 하나뿐이다.**
- **배포 후 수동 검증(1회)** — 음수 순서값은 `application-prod.yaml`에만 있어 local
  프로파일에서는 검증되지 않는다(local은 여전히 order 1이라 로컬 검증 절차 자체를
  두지 않는다). `main` 배포로 새 설정이 반영된 뒤, 실제 4xx 한 건과 5xx 한 건을
  각각 Sentry 콘솔에서 확인한다.
