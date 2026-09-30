# ADR-0013: 소셜 로그인 재도입 (구글/카카오/애플)

- 상태: 승인
- 날짜: 2026-08-10

## 맥락
ADR-0004에서 MVP 스코프 축소를 위해 소셜 로그인을 제외하고 익명 기기(DEVICE) 방식으로
사용자를 식별했다. 앱 출시 이후 랭킹·소셜 스터디 등 경쟁 기능 도입이 예정되어 있어
계정 기반 인증이 필요해졌고, 사용자 데이터 보호(기기 분실 시 복구)를 위해서도
소셜 로그인이 시급해졌다.

## 결정
1. ADR-0002(앱 네이티브 소셜로그인 + 자체 JWT)를 재활성화한다.
2. 구글에 더해 **카카오**와 **애플** 소셜 로그인을 추가한다.
3. 기존 DEVICE 유저가 소셜 계정을 연동하면 기존 데이터(공부 세션 등)를 유지한 채
   로그인 방식만 전환된다 (`POST /api/auth/link`).
4. DEVICE 등록(`POST /api/users`)도 JWT를 발급하여 인증 전환 기간 동안 호환성을 유지한다.
5. 유저당 소셜 프로바이더는 1개(단일 프로바이더).

## 구현 요약
- AUTH-DISABLED 주석 31개 지점 해제 (SecurityConfig, JWT, OAuth, 테스트 등)
- `KakaoTokenVerifier`: 카카오 OIDC tokeninfo API로 ID 토큰 검증
- `AppleTokenVerifier`: Apple JWKS 기반 JWT 서명 검증 (24시간 캐시)
- `POST /api/auth/link`: DEVICE 유저의 소셜 계정 연동 API
- 세션/통계 API의 userId를 요청 본문에서 `@AuthenticationPrincipal`로 교체
- CORS 설정을 WebConfig(MVC 레벨)에서 SecurityConfig(Security 필터 체인)로 이관

## 폐기
- ADR-0004 (로그인 MVP 제외) → 폐기

## 결과
- 모든 API 엔드포인트가 JWT 인증 필수 (permitAll: 로그인/리프레시/링크/유저등록/헬스/Swagger)
- DEVICE 유저는 등록 시 JWT를 발급받아 인증된 API를 사용할 수 있음
- 소셜 로그인 전환 후 기기 UUID로는 더 이상 접근 불가
