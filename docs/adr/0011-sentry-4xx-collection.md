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
- **`application-prod.yaml`의 `exception-resolver-order` 설정을 제거한다.** 이 설정이
  정확히 ADR-0010이 4xx를 걸러내던 메커니즘이었다. 지우면 Sentry의
  `SentryExceptionResolver`가 기본값(최우선순위)으로 돌아가 `@ExceptionHandler`보다
  먼저 모든 예외를 캡처한다
- **`GlobalExceptionHandler`의 수동 `Sentry.captureException` 호출을 제거한다.** 자동
  resolver가 이미 캡처하므로 남겨두면 5xx가 이슈 2개로 중복된다
- **알림 정책은 바꾸지 않는다.** Sentry 알림은 여전히 쓰지 않고, CloudWatch 경보가
  5xx 알람을 그대로 전담한다
- **4xx/5xx 심각도(level) 구분은 하지 않는다.** 지금은 전부 동일하게 캡처하는 것으로
  충분하다. 이슈 목록이 실제로 훑어보기 불편해지면 그때 `beforeSend` 콜백으로 구분하는
  방법을 별도로 검토한다

## 결과

- HTTP 응답(상태코드·바디)은 전혀 바뀌지 않는다. Sentry 캡처는 resolver 체인의
  부수효과일 뿐 `@ExceptionHandler`의 리턴값에 관여하지 않는다
- Sentry 콘솔의 이슈 발생량이 크게 늘어난다. 알림을 쓰지 않으므로 당장 문제는 없다
- `Sentry.captureException`이 static이라 "실제로 Sentry에 도달하는지"는 여전히 자동
  테스트로 검증하지 않는다 (ADR-0010과 동일한 판단). 배포 후 1회 수동 검증하고, 이후
  4xx 이슈가 전혀 없으면 설정을 의심한다
- 설계: `docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md`
