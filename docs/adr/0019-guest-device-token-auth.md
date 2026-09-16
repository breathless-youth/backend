# ADR-0019: 비회원 기기 토큰 인증 (DEVICE access·refresh)

- 상태: 승인
- 날짜: 2026-09-16
- 티켓: BY-526 (FE 짝 티켓 BY-509)

## 맥락
ADR-0004 이후 모든 API가 무인증이었고 요청의 `userId`(query·body·path)를 그대로 신뢰했다.
ADR-0013으로 소셜 로그인과 JWT를 재도입했다가 2026-09-06 사업 결정으로 다시 파킹하면서
그 상태로 돌아왔다 — userId만 알면 남의 프로필·세션·방을 읽고 쓸 수 있고, STOMP도
핸드셰이크 URL의 `?userId=`를 그대로 믿었다. 소셜 로그인은 여전히 후순위지만 사칭 경로는
먼저 막아야 하고, FE(BY-509)는 토큰 인프라를 이미 구현해 서버 배포만 기다리고 있다.

## 결정
1. **토큰 모델은 ADR-0002 그대로.** access는 JWT(HS256, 30분, 클레임은 `sub`=userId뿐),
   refresh는 opaque UUID(30일, SHA-256 해시만 저장, 1회용 회전, 사용된 토큰이 다시 오면 탈취
   의심으로 그 유저의 refresh 전량 폐기). 코드는 파킹돼 있던 것을 되살렸다(refresh 회전은
   `feature/BY-383-auth-contract`의 하드닝 버전).
2. **발급 진입점은 `POST /api/users` 하나.** 응답이 `{userId, isNew, accessToken, refreshToken}`.
   별도 login API는 없다. 같은 deviceId 재등록은 그 유저의 refresh를 **전량 폐기하고 새 쌍**을
   준다 — DEVICE 유저는 기기 하나라는 전제라 재등록이 곧 다른 곳의 토큰 무효화다. 회전(refresh)은
   재사용 감지용 tombstone을 남겨야 하므로 이 폐기 경로를 쓰지 않는다. 토큰 발급 합성은 컨트롤러가
   한다 — `UserService.register`는 토큰을 모르므로 시더 같은 내부 호출이 refresh 행을 만들지 않는다.
3. **신원은 오직 principal.** 모든 컨트롤러가 `@AuthenticationPrincipal Long userId`로 받고 요청의
   userId 채널은 전부 제거했다. `GET/PATCH /api/users/{userId}/profile`은 `/api/users/me/profile`,
   `POST /api/rooms`는 본문 없음. **병행 기간을 두지 않는다(빅뱅)** — 구버전 앱은 강제
   업데이트(BY-531)로 막고, prod 배포를 앱 출시와 같은 시점에 맞춘다.
4. **인증 없이 열린 경로**는 `POST /api/users`, `POST /api/auth/refresh`, `GET /ws`(핸드셰이크),
   `/actuator/health`, Swagger(dev만), ERROR 디스패치뿐이다. `/api/users`는 메서드를 한정한다 —
   경로 전체를 열면 `/me/profile`이 뚫린다. `metrics`·`wsstats` 등 나머지 actuator도 토큰이 필요하다.
5. **401은 다른 에러와 같은 `{code, message}` 모양.** access 누락·위조·만료는 `UNAUTHORIZED`,
   refresh 재사용·만료·미존재는 `INVALID_REFRESH_TOKEN`. 클라이언트는 code로 가른다 — 전자는
   refresh 후 1회 재시도, 후자는 저장 토큰을 지우고 재등록.
6. **STOMP는 CONNECT 프레임의 `Authorization: Bearer` 헤더로 인증**하고 핸드셰이크 `?userId=`는
   더 이상 읽지 않는다(URL의 토큰은 ALB 액세스 로그로 샌다). 실패는 `MessageDeliveryException`으로
   던진다 — null 반환은 아무 프레임도 나가지 않아 클라이언트가 CONNECTED를 무한 대기한다.
   검증은 CONNECT 시점 한 번이고 접속 중 access가 만료돼도 세션은 유지된다(재접속 전 갱신은
   클라이언트 몫).
7. **로그 컨텍스트의 userId는 `JwtFilter`가 SecurityContext에서 싣는다.** ADR-0016의 결정 3·4
   (BodyAdvice·경로변수 인터셉터)는 폐기. `JwtFilter`는 빈이 아니다 — 빈이면 Boot가 서블릿 필터로
   한 번 더 등록해 시큐리티 체인 밖에서도 돈다.
8. **소셜 로그인(login·link·logout·탈퇴)은 계속 파킹.** `feature/BY-383-auth-contract`와 주석 코드가
   원본이다. ADR-0013 중 "DEVICE 등록도 JWT 발급"(결정 4)만 이 ADR로 선행 구현했다.

## 결과
- userId만으로는 아무것도 할 수 없다. 사칭하려면 access 토큰(30분)이나 refresh(기기 보안 저장소)가
  필요하고, refresh가 새면 재사용 감지가 전량 폐기로 끊는다. deviceId는 여전히 재등록 자격증명이다 —
  기기 보안 저장소 밖으로 내보내지 않는 것은 FE 규칙(BY-509).
- **`JWT_SECRET` 환경변수(32자 이상)가 dev `.env`와 prod 태스크 정의(SSM)에 있어야 한다.**
  `JwtUtil`이 `@Value`로 읽으므로 누락 = 기동 실패. 그동안은 읽는 코드가 없어 빠져도 떴다.
- `refresh_token` 행은 회전마다 tombstone 1개가 남고(30일 만료) 정리 잡은 없다. 재등록·재사용
  감지 때만 유저 단위로 지워진다 → 만료 행 정리 스케줄러는 후속 티켓.
- 부하테스트 스크립트(`docs/qa/v1.2.0/loadtest`)는 토큰 미반영이라 그대로 돌리면 401이다 → 후속 티켓.
- Codex 보안 챌린지가 남긴 후속 과제 (이 ADR 범위 밖으로 둔 것):
  - STOMP 세션은 CONNECT 시점 토큰 수명(30분)을 넘겨 살아 있고, refresh 전량 폐기·재등록도 열린 소켓을
    끊지 않는다. 세션을 토큰 만료에 묶으면 공부 중 30분마다 재접속이 생기므로 제품 결정이 필요하다 —
    대안은 폐기 이벤트 → `SessionRegistry`로 그 유저의 소켓 닫기.
  - 방을 나가도(HTTP leave·방 이동) 기존 브로커 구독은 남는다(이 ADR 이전부터). 멤버십 상실 시 구독 해제.
  - deviceId는 재등록 자격증명인데 `users.provider_user_id`에 평문 저장, dev의 p6spy가 바인딩 값을 찍는다.
    해시 저장은 기존 행 마이그레이션이 필요하다.
  - 회전 tombstone에 상한·정리 잡이 없다. 폐기는 벌크 삭제 한 문장으로 바꿨지만 만료 행 정리·발급 rate limit은 후속.
  - `/actuator/metrics`·`info`·`wsstats`는 아무 등록 기기의 토큰으로 읽힌다. 운영 엔드포인트는 관리 포트 분리 검토.
- 배포는 dev 먼저(FE dev 검증), prod는 앱 출시·강제 업데이트 최소 버전 상향과 동시. 기존 설치는
  업데이트 후 첫 실행에서 저장된 deviceId로 재등록해 같은 userId의 토큰을 받는다(데이터 이관 없음).

## 관련
- ADR-0004의 "모든 API가 무인증이고 userId를 그대로 신뢰하는 임시 상태"는 여기서 끝난다.
- ADR-0013: 결정 4만 선행. 소셜 부분은 파킹 유지.
- ADR-0016: 결정 3·4 폐기, 결정 2의 "쿼리 파라미터 userId" 읽기 제거.
