# 일일 지표 Slack 리포트 설계

- 작성일: 2026-08-09
- 상태: 작성됨
- 관련 ADR: ADR-0009(스트릭 인정 기준 재사용), ADR-0004(로그인 MVP 제외 — 관리자 API를 열지 않는 근거)

## 배경

서비스의 헤비유저와 가입 추이를 정기적으로 확인할 방법이 없다. 현재 지표를 보려면 운영 DB에
직접 붙어 SQL을 쳐야 한다.

처음에는 Redash 같은 BI 도구로 대시보드를 만드는 방향을 검토했다. 그러나 실제 사용자는
개발자 3명이고 확인 빈도는 "가끔"이다. 이 규모에 상시 컨테이너(월 $30 내외) + RDS 접근
보안그룹 + 별도 인증 관리를 유지하는 것은 비용 대비 효용이 낮다.

더 결정적인 이유는 **지표 정의가 도메인 규칙이라는 점**이다. 헤비유저 기준은 ADR-0009가 정한
스트릭 인정 기준(`focusSec ≥ 600`)의 재사용인데, 이 SQL이 BI 도구 안에만 존재하면 ADR이
바뀔 때 테스트가 걸리지 않아 조용히 어긋난다. 백엔드 안에 두면 기존 스트릭 로직과 같은
상수를 공유하고 테스트로 고정된다.

## 목표

매일 오전 10시(KST) Slack 채널로 서비스 핵심 지표 4개를 자동 발송한다. 별도 인프라를
추가하지 않고, 지표 정의를 백엔드 도메인 코드와 테스트 안에 유지한다.

## 범위

- `project.study.metrics` 패키지 신설 — 스케줄러, 리포트 서비스, Slack 발송기, 발송 이력 저장소
- `@EnableScheduling` 활성화 (현재 이 프로젝트에 스케줄러가 전혀 없다)
- Flyway 마이그레이션 1건 — 발송 이력 테이블
- `UserService`·`StudySessionService`에 지표 조회 메서드 추가
- Slack Incoming Webhook 연동 (URL은 SSM Parameter Store → 환경변수)

### 비범위 (Non-goals)

- **웹 대시보드·시각화**: 만들지 않는다. 매일 Slack으로 지표가 배달되므로 "가끔 확인"의
  대부분이 대체된다. 시각화가 실제로 아쉬워지면 그때 Metabase를 얹는다 — 이 설계가 그것을
  막지 않는다.
- **관리자 조회 API**: 열지 않는다. 로그인이 MVP에서 제외되어(ADR-0004) 관리자 엔드포인트를
  안전하게 보호할 인증 수단이 현재 없다. Slack push 방식은 엔드포인트를 노출하지 않으므로
  이 문제를 회피한다.
- **중복 유저 보정**: 하지 않는다. 아래 "알려진 한계" 참고.
- **과거 데이터 백필**: 발송 이력은 이 기능이 켜진 날부터 쌓인다.

## 결정과 근거

### 1. 헤비유저 정의 — ADR-0009 스트릭 인정 기준의 재사용

**헤비유저 = 최근 7일 중 "스트릭 인정일"이 3일 이상인 유저.**

ADR-0009는 스트릭 인정을 이렇게 정의한다: "그 날짜의 세션 중 **하나라도** `focusSec`이
10분(600초) 이상이면 그 날은 스트릭에 잡힌 날로 인정한다. 하루 합계가 아니라 세션 단위
기준이다."

```sql
SELECT user_id, COUNT(DISTINCT stat_date) AS active_days
FROM study_session
WHERE stat_date >= :yesterday - 6
  AND focus_sec >= 600
GROUP BY user_id
HAVING COUNT(DISTINCT stat_date) >= 3
ORDER BY active_days DESC, user_id;
```

세 가지 대안을 검토하고 기각했다.

- **`studySec`(총 공부시간) 기준**: 기각. ADR-0009가 명시적으로 `focusSec`을 택했다 —
  "방에 머문 시간이 아니라 실제로 집중한 시간을 기준으로 삼는다". `studySec`을 쓰면 제품
  안에 "공부한 날"의 정의가 두 개 생긴다.
- **하루 합계 10분 기준**: 기각. 같은 이유로 ADR-0009의 세션 단위 기준과 어긋난다.
- **자정 분할 조각 병합 후 판정**: 기각. ADR-0005에 따라 자정을 넘긴 세션은 `focusSec`이
  비율 분배되므로(예: 12분 → 5분 + 7분), 병합하면 **헤비유저로 잡히는데 앱 스트릭에는
  안 잡히는** 유저가 생긴다. 대시보드 숫자가 사용자가 보는 화면과 어긋나는 것이 자정
  경계 정확도보다 나쁘다. 자정 처리를 바꾸고 싶다면 ADR-0009 자체를 고쳐 양쪽이 함께
  움직이게 해야 한다.

임계값 `600`은 `StudySessionService.MIN_STREAK_FOCUS_SEC`에 이미 private 상수로 존재한다.
새로 정의하지 않고 **`StudySessionService`가 조회 메서드를 노출**해 상수를 private으로
유지한다 — 기준이 계속 한 곳에만 존재하게 하기 위함이다.

### 2. 지표 4개

기준일은 **한국 시간 어제**(`(now() AT TIME ZONE 'Asia/Seoul')::date - 1`). `stat_date`가
이미 KST 기준으로 계산된 값이라 결이 맞는다.

**오늘이 아니라 어제를 기준으로 삼는 이유**: 발송 시각이 오전 10시라 오늘 데이터는 10시간치
부분 집계다. 오늘을 포함하면 같은 유저 집합인데도 발송 시각에 따라 수가 흔들려 날짜 간
비교가 성립하지 않는다. 어제를 앵커로 잡으면 항상 완결된 하루 단위로 집계된다.
따라서 헤비유저 조회 구간은 `[어제-6일, 어제]`로 **어제를 포함한 7일**이다.

| 지표 | 산출 | 소유 도메인 |
|---|---|---|
| 총 가입 수 | `count(*) FROM users` | `user` |
| 어제 신규 가입 | `created_at`이 어제 KST 범위 | `user` |
| 헤비유저 수 + `userId` 목록 | 위 쿼리 (인정일수 내림차순) | `studysession` |
| 어제 10분 이상 세션 수 | `stat_date = 어제 AND focus_sec >= 600` | `studysession` |

마지막 지표도 `focus_sec` 기준이다 — 헤비유저·스트릭과 같은 잣대라야 "어제 10분 세션 N건"과
"헤비유저 M명"을 나란히 읽을 수 있다.

헤비유저는 수만이 아니라 `userId` 목록까지 보낸다. 원래 요구가 "헤비유저를 보고 싶다"였고,
현재 유저 규모(50명 수준)에서는 목록을 넣어도 메시지가 길어지지 않는다. 각 유저의 인정일수
(3~7일)를 함께 표기해 누가 더 깊이 쓰는지 구분할 수 있게 한다.

지표는 **각자 소유 도메인의 서비스에서** 가져온다. `metrics` 패키지가 `study_session`·`users`
테이블을 직접 조회하지 않는다 — CLAUDE.md의 "도메인 간 직접 참조 최소화"를 따르고,
ArchUnit 규칙(`controllerShouldNotAccessRepository`)과도 결이 맞는다.

### 3. 하루 1회 발송 보장 — 날짜 선점

ECS `desired_count = 1`이지만 **배포 중에는 태스크가 일시적으로 2개**가 된다(무중단 배포).
그 시간대에 10시가 걸리면 알림이 두 번 간다. 태스크 재시작이 10시 정각에 겹치는 경우도
마찬가지다.

지표를 조회하기 **전에** 오늘 날짜를 선점한다. 삽입된 행이 0이면 이미 다른 인스턴스가
보냈다는 뜻이므로 즉시 종료한다.

```sql
-- V8__daily_report_log.sql
CREATE TABLE "daily_report_log" (
    "report_date" date PRIMARY KEY,
    "sent_at"     timestamptz NOT NULL
);
```

```sql
INSERT INTO daily_report_log (report_date, sent_at) VALUES (:today, now())
ON CONFLICT (report_date) DO NOTHING
```

`UserRepository.insertIfAbsent`와 같은 관용구다 — 이 레포는 동시성을 애플리케이션 락이 아니라
DB 제약으로 처리해왔고, 그 패턴을 유지한다.

**선점을 먼저 하는 쪽의 트레이드오프**: 발송이 실패하면 그날 알림이 누락된다(자동 재시도
없음). 반대로 발송 후에 기록하면 중복 발송이 가능해진다. 매일 오는 알림이므로 하루 누락이
중복보다 낫다고 판단했다. 실패는 Sentry로 올라오므로 필요하면 수동으로 확인한다.

### 4. Slack 발송 — Incoming Webhook

기존 `infra/monitoring.tf`의 AWS Chatbot 연동은 **CloudWatch 알람 전용**이라(SNS 토픽 구독)
임의 시각의 임의 메시지를 보내는 용도로 재사용할 수 없다. Slack Incoming Webhook을 새로
발급해 사용한다.

Webhook URL은 시크릿이므로 이 레포의 기존 패턴을 그대로 따른다 — SSM Parameter Store에
저장하고 ECS 태스크 정의가 환경변수로 주입한다(DB 자격증명·Sentry DSN과 동일한 경로).

```yaml
# application-prod.yaml
metrics:
  slack:
    # URL이 없으면 발송기가 no-op으로 동작한다. Sentry DSN과 같은 이유로 기본값을 둔다 —
    # 모니터링 설정 하나가 없다고 API 전체가 기동 실패하는 쪽이 더 나쁘다.
    webhook-url: ${SLACK_WEBHOOK_URL:}
```

HTTP 호출에는 `StudyApplication.java:21`에 이미 등록된 `RestClient.Builder` 빈을 재사용한다.
새 의존성은 추가하지 않는다.

### 5. `@EnableScheduling` 신설

이 프로젝트에는 현재 `@Scheduled`가 하나도 없다. `config` 패키지에 스케줄링 설정을 추가한다.

```java
@Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
```

`zone`을 명시하는 이유는 컨테이너의 기본 타임존에 의존하지 않기 위함이다 — ECS 태스크는
UTC로 뜨므로 zone 없이는 오후 7시에 발송된다.

## 구성요소

| 클래스 | 책임 | 의존 |
|---|---|---|
| `DailyReportScheduler` | 시각 트리거만. 로직 없음 | `DailyReportService` |
| `DailyReportService` | 날짜 선점 → 지표 수집 → 발송 조율 | `UserService`, `StudySessionService`, `SlackNotifier`, `DailyReportRepository` |
| `SlackNotifier` (인터페이스) | 메시지 발송 | — |
| `SlackWebhookNotifier` | `RestClient`로 Webhook POST. URL이 비면 no-op | `RestClient.Builder` |
| `DailyReportRepository` | 발송 이력 선점 | — |
| `DailyReport` (record) | 지표 4개를 담는 DTO | — |

`SlackNotifier`를 인터페이스로 두는 이유는 단위 테스트에서 가짜 구현으로 대체하기 위함이다.
실제 HTTP 호출은 통합 테스트 범위 밖으로 둔다(외부 서비스에 의존하는 테스트는 불안정하다).

## 데이터 흐름

```
10:00 KST → DailyReportScheduler.run()
              ↓
            DailyReportService.sendDailyReport()
              ↓ ① INSERT ... ON CONFLICT DO NOTHING  → 0행이면 즉시 종료(이미 발송됨)
              ↓ ② UserService.countTotal() / countRegisteredOn(어제)
              ↓    StudySessionService.findHeavyUsers(from, to, 3)
              ↓    StudySessionService.countQualifyingSessionsOn(어제)
              ↓ ③ SlackNotifier.send(DailyReport)
```

## 에러 처리

| 상황 | 동작 |
|---|---|
| 날짜 선점 실패(0행) | 정상 종료. INFO 로그만 남긴다 — 배포 중 겹침은 예상된 상황이다 |
| 지표 쿼리 실패 | 전체 중단 + Sentry. 부분 지표만 보내면 숫자를 오해하게 된다 |
| Slack 발송 실패 | Sentry + ERROR 로그. 자동 재시도 없음(위 3번 트레이드오프) |
| Webhook URL 미설정 | **날짜를 선점하지 않고** 즉시 종료. 기동 시 WARN 로그 1회로 누락을 발견할 수 있게 한다 |

Webhook URL이 없을 때 날짜를 선점하지 않는 이유는, 선점해 두면 나중에 URL을 설정하고 태스크를
다시 띄워도 그날 리포트가 영영 발송되지 않기 때문이다. 발송할 수단이 없으면 아무 기록도
남기지 않는 편이 복구 가능하다.

스케줄러 메서드 자체에서 예외가 밖으로 나가면 Spring이 로그만 남기고 삼킨다. Sentry로
올리려면 서비스 안에서 명시적으로 캡처해야 한다.

## 테스트 계획

**단위 테스트 — `DailyReportServiceTest`**
- 날짜 선점에 실패하면(0행) 지표 조회도 발송도 하지 않는다 (`verify(never())`)
- 선점에 성공하면 지표 4개를 모아 발송기에 넘긴다
- 발송이 예외를 던져도 스케줄러 밖으로 전파되지 않는다
- 메시지 포맷 — 헤비유저 0명일 때 목록 자리에 무엇이 오는지 포함

**통합 테스트 — `HeavyUserQueryIntegrationTest` (Testcontainers 실제 PostgreSQL)**

헤비유저 판정이 이 설계의 핵심이므로 경계값을 실제 DB로 고정한다.

- `focus_sec = 599` → 제외, `600` → 포함
- 인정일 2일 → 제외, 3일 → 포함
- 구간 경계: `stat_date = 어제 - 6` → 포함, `어제 - 7` → 제외
- 오늘(`stat_date = 오늘`) 세션은 집계에 들어가지 않는다 — 어제 앵커 결정을 고정하는 테스트
- 같은 날 세션이 여러 개일 때 하루로 계산되는지 (`COUNT(DISTINCT stat_date)`)
- 같은 날 10분 미만 세션 여러 개 + 10분 이상 세션 1개 → 그 날은 인정 (ADR-0009의
  "하나라도" 규칙)
- 자정 분할 조각(각각 10분 미만) → 인정되지 않음. **기존 스트릭과 동일한 동작임을
  고정하는 테스트**다 — 향후 누군가 병합 로직을 넣으면 이 테스트가 실패해 ADR-0009와의
  불일치를 알린다

**통합 테스트 — 하루 1회 보장**
- 같은 날짜로 두 번 호출하면 두 번째는 발송기를 호출하지 않는다

## 예상 결과

- 매일 오전 10시 Slack에 지표 4개가 도착한다
- 헤비유저 판정 규칙이 스트릭과 한 곳(`MIN_STREAK_FOCUS_SEC`)을 공유해, ADR-0009가 바뀌면
  양쪽이 함께 움직인다
- 새 인프라·추가 비용 없음. 기존 배포 파이프라인 그대로

## 알려진 한계

**총 가입 수가 실제보다 크다.** 구버전 앱 빌드가 신규 설치 첫 실행에서 `POST /api/users`를
서로 다른 UUID로 두 번 호출해 유저가 2건씩 생성되는 문제가 있다(2026-08-06 기준 11쌍 확인).
프론트 수정은 main에 병합되었으나(`281990f`) 이미 배포된 빌드에는 소급 적용되지 않아, 새
빌드가 퍼질 때까지 계속 발생한다.

**보정하지 않는다.** 어느 계정이 중복인지 서버가 확실히 판별할 수 없어(두 요청의 deviceId가
서로 다르다) 어떤 보정도 추측이 된다. 잘못된 보정값을 매일 기록하는 것보다 원값을 보내고
한계를 아는 편이 낫다. 새 빌드가 퍼지면 자연히 정확해진다.

이 문제의 서버 측 완화책은 별도 설계로 다룬다.
