# ADR-0016: 로그 컨텍스트 — MDC(userId·requestId)와 HTTP 액세스 로그

- 상태: 승인
- 날짜: 2026-09-04
- 티켓: BY-609

## 맥락
Spring 서버 로그는 stdout에 ECS JSON 한 줄로 찍히고, ECS 태스크의 awslogs 드라이버가 CloudWatch Logs
`/ecs/focus-makers-prod-api`로 보낸다. 그런데 로그 어디에도 "어느 유저의 요청인가"가 없었다. 요청 로그
자체가 없고, userId는 room 도메인의 debug 로그 몇 곳에만 수동으로 찍혀 있는데 prod는 INFO라 나오지도
않는다. 특정 유저의 문의나 장애를 CloudWatch에서 추적할 길이 없었다.

실무 관행은 요청마다 MDC에 requestId·userId를 넣고, 요청당 액세스 로그 한 줄을 남기는 것이다. userId는
보통 인증 뒤 필터에서 SecurityContext로 읽는다. 하지만 이 프로젝트는 JWT가 파킹 상태(ADR-0004)라
userId가 GET은 쿼리 파라미터, POST는 body, STOMP는 핸드셰이크 파라미터에 실린다. 필터는 body를 읽지
않는 것이 원칙이라 관행을 그대로 옮길 수 없었다.

## 결정
1. **MDC 키는 `userId`, `requestId` 두 개.** Boot의 ECS 포맷은 MDC를 JSON 최상위 필드로 실으므로
   이 이름이 곧 CloudWatch Logs Insights 필드명이다. 로그 패턴·logback.xml은 건드리지 않는다.
2. **`RequestLoggingFilter`(체인 맨 앞)가 requestId를 만들고 응답 헤더 `X-Request-Id`로 돌려준다.**
   클라이언트가 보낸 `X-Request-Id`가 있으면 그대로 쓴다. 쿼리 파라미터 `userId`는 여기서 읽는다.
   응답 뒤 액세스 로그 한 줄(method, path, status, 소요 ms)을 INFO로 남기고 finally에서 MDC를 비운다.
   `/actuator/**`는 제외한다.
3. **POST body의 userId는 `RequestBodyAdvice`가 역직렬화 직후 MDC에 넣는다.** 대상은 `UserScopedRequest`
   마커를 구현한 요청 DTO다. 리플렉션 대신 마커를 택한 이유는 "이 DTO의 userId가 로그에 실린다"를 코드에서
   명시하기 위해서다. **userId를 받는 요청 DTO를 새로 만들면 이 마커를 붙여야 한다.**
4. **경로 변수 `{userId}`는 `HandlerInterceptor`가 넣는다.** 경로 변수는 핸들러 매핑이 URL 패턴과 매칭한 뒤에야
   생기므로 필터 시점에는 없다. 매핑 직후 `preHandle`에서 요청 속성의 경로 변수 맵을 읽는다
   (`/api/users/{userId}/profile`이 대상. Codex 리뷰가 잡은 빈틈).
5. **STOMP는 `ExecutorChannelInterceptor`로 핸들러 전후에 넣고 지운다.** `preSend`는 수신 스레드에서
   불려 핸들러 스레드와 다르므로 쓰지 않는다. 세션 이벤트 리스너는 이 경로 밖이라 userId를 메시지에 직접 찍는다.
6. **`@Async` 실행기에는 `TaskDecorator`로 MDC를 복사한다.** 현재 대상은 `roomHistoryExecutor` 하나다.
7. **부하테스트 때는 액세스 로그 로거를 WARN으로 내려 끈다.** CloudWatch 수집은 GB당 과금이라 k6 요청
   수만큼 비용이 붙는다. 태스크 정의 환경변수
   `LOGGING_LEVEL_PROJECT_STUDY_COMMON_LOGGING_REQUESTLOGGINGFILTER=WARN`으로 이미지 재빌드 없이 내린다.
   평상시 prod는 INFO를 유지한다.

## 결과
- Insights에서 `filter userId = 42 | sort @timestamp desc`로 한 유저의 요청 흐름·에러를 본다.
  프론트가 문의 시 응답의 `X-Request-Id`를 건네면 `filter requestId = ...`로 바로 찾는다.
- 로그 볼륨은 요청당 약 300바이트 늘어난다. 현재 트래픽에서는 무시할 수준이다.
- JWT를 다시 켤 때는 필터의 쿼리 파라미터 읽기를 SecurityContext 읽기로 바꾸면 되고, BodyAdvice와
  마커는 그대로 두거나 body에서 userId가 사라지는 시점에 함께 제거한다.
- 로그 기반 알람(메트릭 필터)은 만들지 않는다. 에러 추적은 Sentry(ADR-0010), 흐름 추적은 CloudWatch다.
