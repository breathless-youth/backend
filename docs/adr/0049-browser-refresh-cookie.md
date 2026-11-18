# ADR-0049: 브라우저는 refresh 토큰을 HttpOnly 쿠키로 주고받는다

- 상태: 승인
- 날짜: 2026-11-19
- 티켓: BY-1343
- 관련: ADR-0002, ADR-0013, ADR-0015, ADR-0019, ADR-0043

## 맥락

앱은 refresh 토큰을 기기 보안 저장소에 둔다(ADR-0019). PC 브라우저에는 그런 저장소가 없다. 웹이 refresh를 `localStorage`에 두면 XSS 한 번에 30일짜리 자격 증명이 밖으로 나간다. 그렇다고 refresh 없이 access만 쓰면 30분마다 다시 등록해야 한다.

API는 CSRF 보호를 꺼 둔 무상태 서버다. 인증은 `Authorization: Bearer` 헤더로만 하고, 지금까지 쿠키는 쓰지 않았다. CORS도 credentials를 허용하지 않았다.

## 결정

1. 요청에 `Refresh-Transport: cookie` 헤더가 있을 때만 쿠키 모드로 동작한다. 사용자 정의 헤더라 ADR-0015에 따라 `X-` 접두사를 붙이지 않는다. 앱은 이 헤더를 보내지 않으므로 앱의 요청과 응답은 바뀌지 않는다.
2. 쿠키 모드에서 `POST /api/users`(v2), `POST /api/auth/refresh`, `POST /api/auth/link`는 새 refresh를 쿠키로만 주고, 응답 본문에서 `refreshToken` 필드를 뺀다. access 토큰은 지금처럼 본문으로 준다.
3. 쿠키 속성은 아래와 같다.
   - 이름 `__Host-refresh_token`: 이 접두사가 붙으면 브라우저는 Secure, `Path=/`, Domain 없음을 모두 갖춘 쿠키만 받는다.
   - `HttpOnly`: 스크립트가 refresh를 읽지 못해 XSS로 값이 새지 않는다.
   - `Secure`: HTTPS로만 오간다.
   - `SameSite=Strict`: 다른 사이트에서 시작된 요청에는 실리지 않는다.
   - `Path=/`: `__Host-` 접두사가 요구하는 값이다.
   - Domain 없음: 발급한 API 호스트에만 실리고 하위 도메인으로 퍼지지 않는다.
   - `Max-Age`: refresh 만료 시간(`refresh-token.expiration`)과 같다.
4. 이름에 `__Secure-`가 아니라 `__Host-`를 쓴다. `__Secure-`는 Secure만 요구해서, 형제 하위 도메인(예: 넘겨받은 `evil.focusmakers.app`)이 자기 refresh를 `Domain=focusmakers.app`으로 같은 이름의 쿠키로 심을 수 있다. 그러면 피해자의 브라우저가 공격자 계정의 refresh로 재발급을 받아, 공격자 계정에 로그인된 채 기록을 남긴다(로그인 CSRF, 계정 고정). `__Host-`는 Domain 속성을 금지하므로 다른 호스트가 같은 이름의 쿠키를 API 호스트에 심을 수 없다. 대신 `Path=/`라 쿠키가 refresh 경로만이 아니라 이 호스트의 모든 API 요청에 실린다. 값은 인증 경로(등록·refresh·link·logout)에서 헤더가 있을 때만 읽고, 다른 API는 쿠키를 보지 않으며, HttpOnly라 스크립트도 읽지 못한다. 요청 로그와 Sentry는 Cookie 헤더를 기록하지 않는다(운영 Sentry는 `send-default-pii: false`). 그래서 실리는 범위가 넓어진 것은 받아들였다.
5. 쿠키는 헤더가 있는 요청에서만 읽는다. CSRF 보호를 꺼 둔 상태에서 같은 사이트의 다른 페이지가 보낸 요청에도 브라우저는 SameSite=Strict 쿠키를 싣는다. 사용자 정의 헤더가 붙은 교차 출처 요청은 CORS preflight를 거쳐야 하므로, 헤더를 조건으로 두면 허용한 origin만 쿠키를 쓸 수 있다. 헤더 값은 대소문자를 가리지 않지만 정확히 `cookie` 하나여야 한다. `cookie,x`처럼 값이 합쳐지거나 헤더가 두 번 오면 앱 요청으로 보고 쿠키를 읽지 않는다. 같은 이름의 쿠키가 둘 이상 오면 어느 쪽도 고르지 않고 토큰이 없는 요청으로 처리한다.
6. `refresh`와 `logout`의 본문은 선택이 됐다. 본문의 `refreshToken`에 값이 있으면 본문을 쓰고, 없을 때만 쿠키를 읽는다. 고른 값은 본문 검증과 같은 검증기로 검사한다. 그래서 64자 상한이 쿠키에도 똑같이 걸리고, 토큰이 어디에도 없으면 앱이 받던 것과 같은 400 `VALIDATION_FAILED`(`refreshToken: 공백일 수 없습니다`, 요청 언어를 따른다)가 나간다. 회전과 재사용 감지는 같은 문자열로 같은 서비스 코드를 타므로 쿠키 경로도 똑같다.
7. 쿠키 모드에서 로그아웃에 성공하거나 refresh가 401 `INVALID_REFRESH_TOKEN`으로 실패하면, 응답에서 같은 이름(`__Host-refresh_token`)·경로(`/`)·속성의 쿠키를 `Max-Age=0`으로 보내 만료시킨다. 쓸 수 없게 된 쿠키가 남으면 브라우저가 같은 401을 되풀이하기 때문이다. 이름이나 경로가 다르면 브라우저는 다른 쿠키로 보고 삭제하지 않으므로 발급과 같은 값을 쓴다. 401의 JSON 본문은 그대로다.
8. CORS의 `allowCredentials`를 켠다. 허용 origin은 지금처럼 패턴 목록으로만 연다. 허용 헤더는 `*`라서 `Refresh-Transport`가 preflight에 걸리지 않는다. dev의 Vercel 미리보기 패턴(`https://*-breathless-youth.vercel.app`)은 뺐다. credentials를 허용하면 허용한 origin의 스크립트가 사용자 쿠키를 실어 API를 부를 수 있다. 이 패턴은 Vercel 계정만 있으면 누구나 맞는 이름으로 배포할 수 있어, 우리 배포만 허용된다고 보장하지 못한다.

## 결과

- 쿠키 모드는 웹과 API가 같은 사이트(`focusmakers.app`)일 때만 동작한다. `web-dev.focusmakers.app`과 `api-dev.focusmakers.app`처럼 등록 가능한 도메인이 같아야 `SameSite=Strict` 쿠키가 실린다.
- `web.sunqstudio.kr`와 Vercel 미리보기는 API와 다른 사이트라 쿠키가 실리지 않는다. 이 도메인의 웹은 쿠키 모드를 쓸 수 없다.
- 로컬 개발은 Vite 프록시로 API를 같은 출처(`http://localhost`)처럼 부를 때만 쿠키가 오간다. Secure 쿠키를 http의 localhost에서 받는지는 브라우저마다 다르다. Chrome과 Firefox는 localhost를 안전한 출처로 보고 받지만, Safari의 동작은 직접 확인해야 한다.
- 앱에서 쓰던 "401이면 deviceId로 다시 등록해 복구"는 브라우저에서 쓰지 않는다. 브라우저에서 refresh를 잃으면 새 비회원으로 시작한다.
- 쿠키가 없는 상태에서 본문 없이 온 refresh·logout은 예전에는 400 `BAD_REQUEST`(본문을 읽을 수 없음)였고 이제는 400 `VALIDATION_FAILED`다. 앱은 늘 본문을 보내므로 영향이 없다.
- 심사용 로그인의 토큰 발급, refresh 요청의 시도 제한, 웹 소셜 로그인의 aud 검증은 이 결정에 넣지 않았다.
- 쿠키를 싣는 응답은 Spring Security 기본 헤더(`Cache-Control: no-cache, no-store, max-age=0, must-revalidate`)로 캐시되지 않는다. 테스트가 `no-store`를 확인한다.

## 검토한 대안

- refresh를 `localStorage`에 둔다: XSS 한 번이면 오래가는 자격 증명이 새어 나가 채택하지 않았다.
- BFF(웹 서버가 토큰을 대신 들고 있는 구조): 정적 배포인 웹에 별도 서버가 필요해 채택하지 않았다.
- 브라우저에서도 deviceId 재등록으로 복구한다: deviceId가 만료 없는 자격 증명이 되고, 스크립트가 읽을 수 있는 곳에 두게 돼 채택하지 않았다.
