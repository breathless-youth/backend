# ADR-0004: 로그인은 MVP에서 제외하고 인증 코드는 주석처리로 보존

- 상태: 폐기 (ADR-0013에서 소셜 로그인 재도입)
- 날짜: 2026-07-24

## 맥락
MVP 스코프를 줄이면서 로그인 기능을 이번 출시에서 제외하기로 했다. 계정 개념은
익명 기기(디바이스 식별) 방식으로 대체할 예정이며 구체 설계는 미정이다.
소셜 로그인 + JWT 인증(ADR-0002)은 이미 dev에 구현·머지된 상태라, 이를 어떻게
비활성화할지(삭제 vs 보존) 결정이 필요했다.

## 결정
- 인증 코드를 삭제하지 않고 **전체 라인 주석처리로 보존**한다. 인증 재도입 시
  되살릴 수 있도록 모든 비활성 지점에 `AUTH-DISABLED` 태그를 남긴다
  (`grep -r AUTH-DISABLED`로 복원 지점 전체 조회 가능)
- 주석처리 범위: SecurityConfig, user 패키지의 인증 코드(controller/dto/jwt/
  oauth/repository/service, RefreshToken 엔티티), 인증 테스트 4개,
  security·jjwt 의존성, oauth/jwt 프로퍼티
- **User·Provider·UserStatus 엔티티는 유지**한다 — Inquiry·UserNotification·
  PushToken·Report가 `@ManyToOne`으로 User를 참조하고, 익명 기기 방식에서도
  users 행 개념은 필요할 가능성이 높다
- Flyway 마이그레이션(V1, V2)은 이력 불변 원칙에 따라 그대로 둔다.
  `ddl-auto: validate`는 매핑된 엔티티만 검증하므로 refresh_token 테이블이
  남아 있어도 무해하다
- ADR-0002는 폐기가 아닌 **보류**로 전환한다 (인증 재도입 시 그 설계를 따름)

## 결과
- **모든 API 엔드포인트가 무인증 공개되고, userId를 요청 본문 값 그대로
  신뢰하는 임시 상태가 된다** — 배포 환경 노출 범위에 주의하고, 랭킹 등
  경쟁 기능 도입 전에 반드시 재검토한다
- 익명 기기 방식 설계 시 users 테이블/User 엔티티를 기기 식별 기반으로
  확장하는 방향을 우선 검토한다
- 인증 재도입 시: AUTH-DISABLED 지점 주석 해제 + 세션 API의 userId를
  principal 기반으로 교체
