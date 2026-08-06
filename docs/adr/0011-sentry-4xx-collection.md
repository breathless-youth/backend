# ADR-0011: Sentry에 4xx도 함께 수집

- 상태: 승인
- 날짜: 2026-08-06

## 맥락

ADR-0010에서는 알림이 노이즈로 죽는 것을 막기 위해 "처리되지 않은 5xx만" Sentry로
보내기로 했다. `NotFoundException`(404)·`ConflictException`(409) 같은 도메인 4xx는
"정상적인 클라이언트 오류"로 분류해 의도적으로 제외했다.

하지만 이 프로젝트는 Sentry의 알림 기능 자체를 쓰지 않는다. 운영 알림은 CloudWatch
경보가 5xx 기준으로 전담하고 있고, Sentry는 이슈를 눈으로 훑어보는 대시보드 용도로만
쓰인다. 즉 ADR-0010이 걱정한 "노이즈가 알림을 죽인다"는 리스크는 Sentry에는 애초에
해당하지 않았다. 반면 4xx 중 일부(특히 `NotFoundException` — 존재하지 않는 리소스
조회)는 잘못된 ID 참조, 삭제된 리소스 재요청 같은 클라이언트 로직 버그의 신호일 수
있는데, 지금까지는 이걸 확인할 방법이 CloudWatch 로그를 직접 뒤지는 것뿐이었다.

## 결정

- **4xx도 5xx와 동일하게 전부 Sentry로 보낸다.** 도메인 예외
  (`BadRequestException`/`InvalidSessionException`, `NotFoundException`,
  `ConflictException`/`DuplicateSessionException`)와 Spring MVC 표준 예외
  (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, 405/415 등)
  를 가리지 않는다
- **`application-prod.yaml`의 `exception-resolver-order`를 지우지 않고 `-2147483647`로
  반전한다.** `SentryExceptionResolver`의 기본 order는 `Integer.MIN_VALUE`(최우선순위)가
  아니라 **1**이고, Spring MVC가 등록하는 예외 리졸버 컴포짓(`@ExceptionHandler`를 실행하는
  `ExceptionHandlerExceptionResolver`를 품고 있다)은 order **0**이다. 즉 기본값 그대로면
  Spring 컴포짓이 먼저 실행되는데, `GlobalExceptionHandler`가
  `@ExceptionHandler(Exception.class)`로 모든 예외를 소비하므로 `DispatcherServlet`은
  컴포짓에서 결과를 받는 순간 체인을 멈춘다 — 뒤에 있는 Sentry 리졸버는 호출조차 되지
  않는다. 그래서 설정을 지우면 4xx는 물론 5xx까지 Sentry에 아무것도 안 남는다.
  0보다 작은 값을 줘야 Sentry가 먼저 캡처한다. 캡처 후 `null`을 반환하므로 응답 생성은
  그대로 `@ExceptionHandler`가 담당한다
  - 곁들여 드러난 사실: 기존 값 `2147483647`도 사실상 무의미했다. ADR-0010에서 4xx가
    걸러졌던 진짜 이유는 리졸버 순서가 아니라 `GlobalExceptionHandler`가 5xx에서만
    수동으로 `Sentry.captureException`을 호출했기 때문이다
- **`GlobalExceptionHandler`의 수동 `Sentry.captureException` 호출을 제거한다.** 위 순서
  반전이 적용된 뒤에는 자동 resolver가 먼저 캡처하므로, 남겨두면 5xx가 이슈 2개로 중복된다
  (순서 반전 없이 수동 캡처만 지우면 아무것도 안 보내지므로, 이 둘은 한 세트다)
- **알림 정책은 바꾸지 않는다.** Sentry 알림은 여전히 쓰지 않고, CloudWatch 경보가
  5xx 알람을 그대로 전담한다
- **4xx/5xx 심각도(level) 구분은 하지 않는다.** 지금은 전부 동일하게 캡처하는 것으로
  충분하다. 이슈 목록이 실제로 훑어보기 불편해지면 그때 `beforeSend` 콜백으로 구분하는
  방법을 별도로 검토한다

## 결과

- HTTP 응답(상태코드·바디)은 전혀 바뀌지 않는다. Sentry 캡처는 resolver 체인의
  부수효과일 뿐 `@ExceptionHandler`의 리턴값에 관여하지 않는다
- Sentry 콘솔의 이슈 발생량이 크게 늘어난다. 알림을 쓰지 않으므로 당장 문제는 없다
- **이벤트 레벨이 `FATAL`, `handled=false`로 고정된다.** `SentryExceptionResolver`는
  이벤트에 `Mechanism(handled=false)`와 `SentryLevel.FATAL`을 직접 박아서 보낸다. 기존
  수동 `Sentry.captureException`의 기본 레벨(`error`)과 달라, 4xx까지 전부 "Fatal /
  Unhandled"로 보이게 된다. 훑어보기가 불편해지면 `beforeSend` 콜백에서 상태코드에 따라
  레벨을 낮추는 것이 조정 지점이다 (지금은 하지 않는다)
- **예외 메시지에 요청 값 일부가 실려갈 수 있다.** `send-default-pii: false`는 요청 헤더·IP
  수집만 막을 뿐, 예외 메시지 본문은 걸러주지 않는다. 예컨대
  `MethodArgumentNotValidException`/`HttpMessageNotReadableException`의 메시지에는 클라이언트가
  보낸 필드 값이 섞일 수 있다. 현재 다루는 값이 세션 타임스탬프 수준이라 실질 위험은 낮지만,
  민감한 입력을 받는 API가 생기면 `beforeSend`에서 메시지를 마스킹해야 한다
- 이벤트가 Sentry 서버에 실제로 도달하는지는 여전히 자동 테스트로 검증하지 않는다
  (SDK 진입점이 static이라는 ADR-0010과 동일한 판단). 배포 후 1회 수동 검증하고, 이후
  4xx 이슈가 전혀 없으면 설정을 의심한다
- 다만 **리졸버 순서는 자동 테스트로 못 박았다** (`SentryExceptionResolverOrderTest`).
  prod yaml의 실제 값을 읽어 Spring 컨텍스트에 적용한 뒤, `DispatcherServlet`과 같은 방식으로
  정렬한 리졸버 체인의 맨 앞이 `SentryExceptionResolver`인지 확인한다. 이 설정 하나가 조용히
  전체 수집을 무력화할 수 있다는 것이 이번에 확인됐기 때문이다
- 설계: `docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md`
