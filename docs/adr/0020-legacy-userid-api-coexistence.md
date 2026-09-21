# ADR-0020: 구 앱 userId 계약과 토큰 인증의 병행 — API-Version 헤더 expand/contract

- 상태: 승인
- 날짜: 2026-09-18
- 티켓: BY-676

## 맥락
ADR-0019(BY-526)로 모든 보호 API가 `Authorization: Bearer` + `@AuthenticationPrincipal Long userId`로
바뀌었고 요청 userId 채널은 전부 제거됐다. 그 ADR의 결정 3은 "병행 기간을 두지 않는다(빅뱅)"였다 —
구 앱은 강제 업데이트(BY-531)로 막고 prod 배포를 앱 출시에 맞춘다는 전제였다.

그러나 서비스가 운영 중이고, 스토어에 나가 있는 구 앱(v1.2.x)은 **토큰도 `API-Version` 헤더도
보내지 않으며** 강제 업데이트 시점은 정해지지 않았다. FE는 구 앱이 강제 업데이트 전까지 동작하고
새 앱을 같은 서버에서 테스트할 수 있기를 요청했다. 즉 서버가 한동안 두 계약을 동시에 지원해야 한다.

## 결정
1. **구분 신호는 `API-Version` 요청 헤더다(ADR-0015 그대로).** 헤더가 없거나 `1`이면 구 앱
   계약(v1.2.1과 동일), `2`면 토큰 계약. 구 앱은 헤더를 못 보내므로 "헤더 없음 = 기본버전 1"이
   병행의 핵심 장치다. **새 앱은 모든 HTTP 요청에 `API-Version: 2`를 붙인다**(WebSocket은 헤더가
   없으므로 CONNECT의 Authorization만으로 충분하다).
   - `Authorization` 유무로 구분하는 대안은 기각 — 인증과 계약 모양이 섞여 나중에 걷어내기 어렵고,
     새 앱의 access가 만료됐을 때 구 핸들러로 흘러 401 대신 엉뚱한 에러가 난다.
2. **구 앱 핸들러는 `version = "1"` 어댑터로 분리한다.** 도메인마다 `Legacy*Controller`가 v1.2.1
   모양의 DTO(`Legacy*Request`)에서 userId를 꺼내 기존 토큰 계약 컨트롤러의 메서드에 그대로
   위임한다 — 로직 중복이 없고, contract는 이 클래스들을 지우는 것이다. 기존 컨트롤러는
   `version = "2"`(클래스 레벨; `UserController`만 등록 경로 때문에 메서드 레벨).
   - 등록 `POST /api/users`도 v1 핸들러를 따로 둔다. 응답에 토큰이 추가된 것은 additive지만,
     구 앱 JSON 파서가 모르는 필드를 무시한다는 가정에 기대지 않고, 구 앱이 실행마다 부르는
     등록이 refresh 토큰을 헛되이 발급·폐기하지 않게 한다. v1 응답은 `{userId, isNew}`.
   - `POST /api/auth/refresh`는 v1.2.1에 없던 경로라 버전 없이 둔다(모든 버전에 매칭).
   - userId 누락은 `@NotNull`로 400이다(v1.2.1은 500/404였음). 구 앱은 항상 보내므로 영향 없다.
3. **시큐리티는 "전부 permitAll"이 아니라 헤더 기반 매처다.** `LegacyApiRequestMatcher`가
   "구 앱이 쓰는 (메서드, 경로) 14개" AND "헤더 없음/1"일 때만 permitAll이고 나머지는 ADR-0019
   그대로 `authenticated`. 그래서 `API-Version: 2` 요청은 토큰이 없거나 만료면 계속 401이라 FE의
   refresh 로직이 보존되고, 새로 추가되는 엔드포인트는 기본으로 보호된다. 프로필 경로는 숫자
   userId만 연다 — `/{userId}/profile`을 문자열까지 열면 `/me/profile`이 헤더 없이 뚫린다.
   - 헤더 `1.0` 같은 변형은 매처가 v2로 보고 인증을 요구한다. 구 앱은 헤더를 안 보내므로 무관.
4. **STOMP도 병행한다.** CONNECT에 `Authorization` 헤더가 아예 없을 때만 핸드셰이크 `?userId=`
   (숫자)를 principal로 쓴다(v1.2.1의 `HandshakeInterceptor` 복원). 헤더가 있는데 Bearer가 아니면
   새 앱의 잘못이므로 폴백하지 않고 거부한다. URL의 userId가 ALB 로그에 남는 것은 v1.2.1과 같다.
5. **구 호출량을 잰다.** `RequestLoggingFilter`가 유효 버전(헤더 없으면 `1`)을 MDC `apiVersion`에
   실어 prod JSON 로그에 최상위 필드로 남긴다. CloudWatch Logs Insights에서
   `filter apiVersion = "1" and path like /api/`로 집계해 contract 시점을 판단한다(ADR-0015 결정 5).
6. **배포는 두 번(expand → contract)이다.** 프로퍼티 토글로 끄는 방식은 ECS 태스크 정의 갱신이
   결국 배포와 같고 죽은 코드가 남아 기각했다.
   1. expand 릴리즈: 이 변경. prod 롤링 배포 동안 구 태스크·새 태스크 모두 구 앱을 받는다.
      전제: prod 태스크 정의에 `JWT_SECRET`(없으면 기동 실패로 롤링이 멈추고 구 태스크가 남는다).
   2. FE 새 앱 출시(`API-Version: 2` + 토큰) → `apiVersion=1` 호출량 관찰 → BY-531 강제 업데이트.
   3. contract 릴리즈(후속 티켓): 아래 삭제 목록을 지운다.
7. **감수하는 위험.** 병행 기간 동안 구 앱 경로는 지금 prod처럼 userId 사칭이 가능하다. 현상
   유지이지 후퇴는 아니며, 강제 업데이트가 닫는다. 새 앱이 헤더를 빠뜨리면 구 핸들러로 가서
   400(`userId` 누락 또는 `/me` 형식 오류)이 난다 — 401이 아니므로 FE 버그로 바로 드러난다.

## 구 앱 표면 (v1.2.1 = prod 계약, `git diff v1.2.1 dev`로 확인)
| # | 메서드·경로 | userId 위치 | 토큰 계약(v2) |
|---|---|---|---|
| 0 | `POST /api/users` | — (응답 `{userId, isNew}`) | `{isNew, accessToken, refreshToken}` — userId는 토큰 `sub` (BY-715) |
| 1 | `GET /api/users/{userId}/profile` | path | `GET /api/users/me/profile` |
| 2 | `PATCH /api/users/{userId}/profile` | path | `PATCH /api/users/me/profile` |
| 3 | `POST /api/rooms` | body `{userId}` | 본문 없음 |
| 4 | `POST /api/rooms/join` | body `{userId, inviteCode}` | `{inviteCode}` |
| 5 | `POST /api/rooms/{roomId}/leave` | `?userId=` | 파라미터 없음 |
| 6 | `POST /api/study-sessions` | body `userId` + 기존 필드 | userId 없는 body |
| 7 | `GET /api/study-sessions/{id}` | `?userId=` | 없음 |
| 8 | `PUT /api/study-sessions/active` | body `userId` + 기존 필드 | userId 없는 body |
| 9 | `GET /api/study-sessions/active` | `?userId=` | 없음 |
| 10 | `POST /api/study-sessions/recovery` | `?userId=` | 없음 |
| 11 | `GET /api/stats` | `?userId=&date=` | `?date=` |
| 12 | `GET /api/stats/streak` | `?userId=` (+from/to) | from/to만 |
| 13 | `GET /api/stats/period` | `?userId=` (+from/to/compare*) | userId 없음 |
| 14 | `POST /api/rtc-stats` | body `userId` + 기존 필드 | userId 없는 body |
| WS | `GET /ws?userId=` + CONNECT에 Authorization 없음 | 세션 속성 | CONNECT `Authorization: Bearer` |

`GET /api/stats/study-days`는 토큰 계약에서 추가된 경로라 구 앱 버전이 없다.

## 구현 요약
- Spring 7 `VersionRequestCondition`: 버전 없는 매핑은 모든 요청 버전에 매칭, 고정 버전은
  정확히 일치해야 한다(`handleMatch`). 같은 경로에 1·2가 있으면 요청 버전과 같은 쪽이 선택되고,
  불일치(`API-Version: 2`로 `/{userId}/profile`)는 400이다. 클래스 레벨 `version`은 메서드에
  버전이 없을 때 상속된다.
- `ApiVersionConfig.HEADER`/`DEFAULT_VERSION` 상수를 매처·로그가 공유한다.
- 경로변수 타입 불일치(`/api/users/me/profile`에 헤더 없음 등)는 `GlobalExceptionHandler`가
  `TypeMismatchException`을 400으로 받는다 — 전에는 generic 핸들러가 500·Sentry로 흘렸다.
- Swagger: 레거시 오퍼레이션은 `deprecated` + `[구 앱 전용 · API-Version 없음]` 접두, 문서 설명에
  헤더 규칙. 예외는 과목 API(`/api/subjects`)뿐이다 — 구 앱이 부르지 않는 새 API라 기본버전(1) 하나로
  통일했다(ADR-0021 2026-09-21 갱신). 토큰은 그대로 필요하다.
  테스트 헬퍼 `AuthTestSupport.asUser`는 `API-Version: 2`를 함께 싣는다 — 인증된 요청은
  곧 토큰 계약이다.
- 검증: `LegacyApiSecurityTest`(14경로 × 헤더 없음/1/2), `Legacy{User,Room,StudySession,RtcStat}ApiTest`,
  `ApiVersionApiTest`, `UserIdChannelInterceptorTest`(STOMP 폴백), `RequestLoggingIntegrationTest`(MDC).

## contract 시 삭제 목록 (후속 티켓)
- `Legacy{User,Room,StudySession,StudySessionStats,RtcStat}Controller` 5개,
  `Legacy{UserRegisterResponse,RoomCreateRequest,RoomJoinRequest,StudySessionCreateRequest,ActiveSessionSnapshotRequest,RtcStatRequest}` 6개
- `LegacyApiRequestMatcher` + `SecurityConfig`의 `.requestMatchers(new LegacyApiRequestMatcher()).permitAll()`
- `WebSocketConfig.LegacyUserIdHandshakeInterceptor` + `authenticate`의 폴백 분기(`legacyPrincipal`)
- `Legacy*ApiTest` 4개, `LegacyApiSecurityTest`, `UserIdChannelInterceptorTest`의 폴백 케이스,
  `websocket.html` §1 "구 앱 호환" 행, ADR-0019 취소선 정리
- 유지: `version = "2"` 속성(ADR-0015 모델), `apiVersion` MDC(관측 가치), `TypeMismatchException` 400

## 결과
- Codex 독립 리뷰(P2 1건: 세션 테스트 픽스처가 새벽엔 미래 시각 — 어제 날짜로 수정)와 보안 챌린지
  (P1 1건, P2 2건) 반영. 토큰 전용(v2) 핸들러에 인증 없이 닿는 경로는 발견되지 않았고, 헤더 변형
  (`1.0`, 앞 공백 `2`)·경로 패턴·메서드 오버라이드 우회도 없었다.
- **P1 반영**: 구 앱 경로는 아무 숫자나 userId로 올 수 있어, 없는 유저의 스냅샷이 공유 버퍼에 들어가면
  flush 때 FK 위반으로 배치가 통째로 롤백되고 정상 유저 행까지 개별 재시도로 밀린다(교차 사용자 부하
  증폭). `LegacyStudySessionController.report`와 STOMP 폴백(`legacyPrincipal`)이 실존 유저만
  통과시킨다(`UserService.getProfile` → 404 / CONNECT 거부). 토큰 계약은 유효한 토큰이 곧 실존 유저라
  이 검사가 필요 없다.
- **후속 티켓 후보(v1.2.1 prod에도 동일하게 있던 결함이라 이 티켓과 분리)**:
  - HTTP 본문 크기 상한(스트리밍 단계)과 `events` 원소 수 상한 — 익명 경로가 열려 있는 동안 더 노출됨
  - STOMP 연결당 구독 수·인바운드 프레임 속도 상한 — `/user/queue/room` 구독은 방 소속 확인 없이 허용됨
  - ALB·컨테이너를 거친 중복 `API-Version` 헤더 해석은 미검증(첫 값을 쓰는 것이 Spring의 동작)
