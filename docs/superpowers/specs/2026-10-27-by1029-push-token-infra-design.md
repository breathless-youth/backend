# 푸시 토큰 등록과 FCM 발송 기반 (BY-1029)

- 작성일: 2026-10-27
- 상위 작업: [BY-1028](https://breathless-youth.atlassian.net/browse/BY-1028) 서버에서 사용자별 FCM 푸시를 보낼 수 있는 기반 마련
- 서브태스크: [BE] BY-1029 (이 문서) · 짝 [FE] BY-1030
- 상태: 구현 완료(2026-10-27)
- 선행 설계: ADR-0010(콘솔 캠페인 발송), ADR-0015·0020(API-Version), ADR-0019(게스트 기기 토큰 인증)

## 1. 배경

앱은 FCM 토큰을 받지만 서버로 보내지 않는다. 그래서 지금은 Firebase 콘솔에서 GA 오디언스로만 알림을 보낼 수 있다.
오늘 공부 안 함, D-Day, 플래너 결과 같은 사용자별 알림을 붙이려면 서버가 토큰을 알고 직접 보낼 수 있어야 한다.

이번 범위는 그 기반까지다. 어떤 알림을 언제 누구에게 보낼지는 시나리오 티켓에서 정한다.

## 2. 확정된 결정 (2026-10-27 대화)

1. 사용자당 토큰 수는 제한하지 않는다. 소셜 로그인이 들어오면 한 계정이 여러 기기를 쓴다.
2. 발송 결과가 `UNREGISTERED`인 토큰만 지운다. 나머지 오류는 우리 쪽 설정 문제일 수 있어 지우지 않고 Sentry로 알린다.
3. 오래된 토큰을 주기적으로 지우는 정리 잡은 두지 않는다. 죽은 토큰은 발송 때 `UNREGISTERED`로 지워지고, 오래 앱을
   안 연 사용자에게 복귀 알림을 보낼 수 있어야 하기 때문이다. 앱이 실행될 때마다 다시 등록하므로 `updated_at`은 마지막
   사용 시각으로 쓰고, 최근 활동 기기만 셀 때는 조회 조건으로 거른다.
4. 알림 데이터에는 `link`(탭하면 열 화면)와 `type`(알림 종류)을 싣는다.
5. 수신 여부는 OS 알림 권한만 따른다. `users.notify_enabled`는 쓰지 않는다.
6. 테스트 발송은 dev 백엔드에서만 연다.
7. 토큰을 등록할 때 앱이 OS 알림 권한 상태를 같이 보낸다. 클릭률의 분모("표시될 수 있었던 발송")를 iOS까지 근사하기 위해서다.
8. 발송마다 종류와 결과 개수를 INFO 로그 한 줄로 남기고, FCM 메시지에 `analytics_label`(= `type`)을 실어 Firebase 콘솔 보고 탭에서 종류별로 집계되게 한다.
9. 발송 기록 테이블, 비교군, `push_id`는 첫 시나리오에서 넣는다. 앱은 BY-1030에서 `push_id`·`route`·`fallback`을 열림 이벤트에 실을 준비만 한다.

## 3. 저장

V1부터 있는 `push_token` 테이블을 쓰고, 알림 권한 컬럼 하나만 더한다(`V30__push_token_notifications_enabled.sql`).

| 컬럼 | 쓰임 |
|---|---|
| `user_id` | 토큰 주인. `users` FK(ON DELETE 없음) |
| `token` | FCM 등록 토큰. 유니크 |
| `platform` | `IOS` · `ANDROID` |
| `notifications_enabled` (새 컬럼) | 마지막 등록 때 OS 알림 권한이 켜져 있었는지. 기존 행이 없어 NOT NULL로 둔다 |
| `created_at` · `updated_at` | 등록 시각 · 마지막 재등록 시각 |

- 엔티티 `PushToken`을 `project.study.push.entity`에 다시 만든다(a709729에서 지운 것과 같은 매핑).
- FK에 삭제 연쇄가 없어서, 사용자를 지우려면 토큰을 먼저 지워야 한다. 탈퇴 기능이 생길 때(소셜 로그인 티켓) 처리한다.

## 4. 등록 API

```
PUT /api/push-tokens        API-Version: 1, 토큰 인증 필요
{ "token": "fcm-token…", "platform": "IOS", "notificationsEnabled": true }
→ 204 No Content
```

- 같은 요청을 몇 번 보내도 결과가 같다(멱등). 앱은 실행할 때마다 부른다.
- 저장은 PostgreSQL 업서트 한 문장으로 한다.
  ```sql
  INSERT INTO push_token (user_id, token, platform, notifications_enabled, created_at, updated_at)
  VALUES (:userId, :token, :platform, :notificationsEnabled, :now, :now)
  ON CONFLICT (token) DO UPDATE
    SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
        notifications_enabled = EXCLUDED.notifications_enabled, updated_at = EXCLUDED.updated_at
  ```
  - 같은 토큰이면 갱신 시각만 바뀐다. JPA 엔티티로 저장하면 값이 같을 때 UPDATE를 건너뛰어 `updated_at`이 멈추고, 마지막 사용 시각으로 쓸 수 없게 된다. 그래서 업서트로 직접 쓴다.
  - 다른 사용자에게 붙어 있던 토큰이면 지금 사용자로 옮긴다. 같은 기기에서 다른 계정으로 로그인한 경우다.
  - 동시에 두 요청이 와도 유니크 인덱스 위에서 한 문장으로 끝나 경합이 없다.
- 검증: `token`은 공백 불가·최대 4096자, `platform`은 `IOS`·`ANDROID`만, `notificationsEnabled`는 필수(어기면 400).

## 5. 발송

```java
PushResult send(Collection<Long> userIds, PushMessage message)
record PushMessage(String title, String body, String link, String type)
record PushResult(int sent, int failed, int removed)
```

- 사용자 ID로 토큰을 모두 찾아 500개씩 나눠 `sendEachForMulticast`로 보낸다(FCM 한 번 호출 상한).
- 메시지는 `notification`(제목·본문)과 `data`(`link`·`type`)를 함께 싣는다. iOS는 소리를 켠다(`sound: default`).
- `fcm_options.analytics_label`에 `type`을 싣는다. 콘솔 보고 탭에서 종류별 발송·열림(Android는 도착·표시까지) 수가 나온다.
- 호출마다 INFO 로그 한 줄을 남긴다: `푸시 발송 type=… sent=… failed=… removed=…`.
- 토큰별 결과 처리:

| 오류 코드 | 처리 |
|---|---|
| 성공 | `sent` |
| `UNREGISTERED` | 행 삭제, `removed` |
| `INVALID_ARGUMENT` · `SENDER_ID_MISMATCH` · `THIRD_PARTY_AUTH_ERROR` | 지우지 않음, `failed` |
| `UNAVAILABLE` · `INTERNAL` · `QUOTA_EXCEEDED` | 지우지 않음, `failed` (재시도는 시나리오가 정함) |

- `failed`가 있으면 호출 한 번에 Sentry 이벤트 하나를 남긴다(코드별 개수). 토큰 값은 로그·Sentry 어디에도 남기지 않는다.
- 자격증명이 없으면 `FirebaseMessaging`을 만들지 않고, `send`는 아무것도 보내지 않은 결과를 돌려준다. 기동 때 경고 로그를 한 번 남긴다(Slack 발송기와 같은 방식).
- 자격증명을 읽으면 그 JSON의 `project_id`를 기동 로그에 남긴다. dev 백엔드에 운영 키를 넣는 실수를 로그로 바로 알아보기 위해서다.

## 6. 테스트 발송

```
POST /api/push-tokens/test   API-Version: 1, 토큰 인증 필요
{ "title": "테스트", "body": "…", "link": "/" }   (모두 생략 가능)
→ 200 { "sent": 1, "failed": 0, "removed": 0 }
```

- 요청한 본인의 토큰으로만 보낸다. `type`은 항상 `test`다.
- 컨트롤러는 `@ConditionalOnProperty(name = "app.push.test-send.enabled", havingValue = "true")`로 켠다. 값이 없으면
  꺼지는 게 기본이라 운영에는 경로 자체가 없고, `application-dev.yaml`에서만 `true`로 둔다. `@Profile`은 ArchUnit이
  막으므로 쓰지 않는다(BY-640의 시딩·API 문서 토글과 같은 방식).

## 7. 설정과 배포

| 환경 | 값 위치 |
|---|---|
| 로컬·테스트 | 없음 → 발송 비활성 |
| dev 백엔드(EC2) | `.env`의 `FCM_SERVICE_ACCOUNT_JSON` = `focusmakers-dev` 서비스 계정 JSON 한 줄 |
| 운영(ECS) | SSM SecureString `fcm/service-account-json` → 태스크 정의 `secrets`로 `FCM_SERVICE_ACCOUNT_JSON` |

```yaml
app:
  push:
    fcm:
      service-account-json: ${FCM_SERVICE_ACCOUNT_JSON:}
```

- 의존성: `com.google.firebase:firebase-admin` 9.11.0을 추가한다(2026-10-27 승인). 처음엔 쓰지 않는 Firestore·Storage를 빼려 했으나, 빼면 `FirebaseOptions`의 기본 JSON 처리기(`google-http-client-jackson2`)까지 빠져 초기화가 `NoClassDefFoundError`로 깨져서 통째로 받는다. Jackson 3(`tools.jackson`)와는 패키지가 달라 부딪히지 않는다.
- `infra`는 별도 저장소라 `ecs.tf` 변경은 그쪽 브랜치·PR로 낸다. SSM 실제 값은 사람이 `aws ssm put-parameter`로 넣는다.
- 배포 순서: 백엔드(발송 비활성 상태로도 안전) → SSM·태스크 정의 → 앱(BY-1030).

## 8. 문서

- ADR-0033 "서버 직접 FCM 발송": 콘솔 캠페인(ADR-0010)과 나란히 쓰는 이유, 토큰 삭제 규칙(정리 잡을 두지 않는 이유), 데이터 계약(`link`·`type`, 나중의 `push_id`).
- Swagger에 두 엔드포인트 설명. `docs/erd.dbml`의 `push_token`에 새 컬럼을 적는다.

## 9. 테스트

| 대상 | 케이스 |
|---|---|
| 등록(통합, Testcontainers) | 새 토큰 저장 / 같은 토큰 재등록 시 `updated_at`·권한 상태 갱신 / 다른 사용자 토큰이면 옮김 / 잘못된 platform·권한 값 누락 400 / 토큰 없이 401 |
| 발송(단위, `FirebaseMessaging` 목) | 사용자 토큰 전부에 보냄 / 501개면 두 번 나눠 보냄 / `UNREGISTERED`만 삭제 / `INVALID_ARGUMENT`는 남김 / 자격증명 없으면 아무것도 안 보냄 / 메시지에 `link`·`type`·`analytics_label`이 실림 |
| 테스트 발송(통합) | 본인 토큰으로만 보냄, `type=test` |

## 10. 범위 밖

- 알림 시나리오와 스케줄러, 발송 시각·문구
- 발송 기록 테이블, 비교군, `push_id`, 하루 발송 상한, 실패 재시도(첫 시나리오)
- Android BigQuery 전달 데이터 내보내기, iOS 알림 서비스 확장(NSE)
- 로그아웃·탈퇴 시 토큰 삭제(소셜 로그인 티켓)
