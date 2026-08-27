# BY-447 세션 진행 스냅샷 보고·서버 자동 확정 — 설계

- 티켓: [BY-447](https://breathless-youth.atlassian.net/browse/BY-447)
- 날짜: 2026-08-27
- 상태: 승인됨

## 배경과 목표

세션 기록은 퇴장 시 1회 최종 제출(`POST /api/study-sessions`, ADR-0003)이 전부다.
클라이언트가 제출 없이 죽으면(백그라운드 강제종료 등) 세션이 유실되고, 로컬스토리지
백업·재제출 복구에는 한계가 있다(저장소 소실, 앱 삭제, 영영 미복귀).

이 작업은 공부 중 클라이언트가 **30초마다 진행 스냅샷을 서버에 보고**하게 하고,
보고가 끊긴 세션을 **서버가 자동 확정**해 통계에 반영한다. 최악 유실은 마지막
스냅샷 이후 30초. 솔로·소셜 공통이며, ADR-0003의 "서버에 미종료 세션 개념을 두지
않는다" 결정을 부분 대체한다(ADR-0014로 기록).

## 핵심 결정 1: draft는 별도 테이블, 확정 테이블은 불변식 유지

진행중 스냅샷은 새 테이블 `active_study_session`(이하 draft)에 세션당 1행으로
UPSERT한다. `study_session`의 "행 = 검증·자정 분할이 끝난 확정 세션" 불변식은
그대로 유지된다.

검토한 대안과 기각 사유:

- **`study_session`에 진행중 행을 UPSERT + `status_event`에 이벤트 행 추가**:
  모든 통계·조회 쿼리에 상태 필터가 필요해지고(누락 시 진행중 세션이 통계에 잡힘),
  진행중 행은 자정 분할 전 상태라 불변식을 깬다. 확정도 "플래그 뒤집기"가 아니라
  자정 분할 때문에 행 갈아끼우기가 되어 행 재사용의 실익이 없다. 멱등 키 자리를
  진행중 행이 점유해 재제출 분기가 3갈래로 늘고, 하트비트·최종 제출·스케줄러가
  같은 행을 경합한다.
- **하트비트 로그 적재(append-only) 후 확정 시 집계**: 30초마다 행이 쌓여
  쓰기량·정리 배치 부담이 크다. 스냅샷 덮어쓰기로 복구 목적은 충분하다.

## 핵심 결정 2: 누적 스냅샷, 클라이언트 시계, REST 공통

- **누적 스냅샷** (증분 델타 아님): 매 보고가 "지금까지의 studySec/focusSec +
  이벤트 전체"를 담는다. 스냅샷 일부가 유실·역순 도착해도 마지막 것 하나만 살아남으면
  되므로 ack/재동기화 프로토콜이 필요 없다.
- **`reportedAt`은 클라이언트 시계**: 자동 확정 시 `endedAt`이 되는 값이다.
  서버 수신 시각을 쓰면 클라 타임스탬프(startedAt·이벤트)와 시계가 섞여 "이벤트가
  세션 밖" 같은 모순이 생기고, 지연 도착 시 측정된 적 없는 시간이 부풀려진다.
  ADR-0003의 클라 타임스탬프 신뢰 모델을 따른다.
- **솔로·소셜 모두 REST 하나** (`PUT /api/study-sessions/active`): 소셜룸 STOMP는
  프레즌스 중계용이고 기록 저장 경로와 무관(ADR-0003). 솔로는 어차피 REST가
  필요하므로 경로를 통일한다.
- **STOMP 하트비트(10초)와 독립**: WS 끊김이 세션을 확정시키지 않고, 세션 확정이
  방 퇴장을 일으키지 않는다. 각자 자기 유예 시계만 본다. 룸 이력과 세션 기록의
  종료 시각이 1분 남짓 어긋날 수 있으나 공부 시간의 원천은 항상 세션 쪽이다.

## API 계약: `PUT /api/study-sessions/active`

공부 중 30초마다 호출. PUT = 진행중 세션 상태를 통째로 덮어쓰는 멱등 UPSERT.

```json
{
  "userId": 1,
  "startedAt": "2026-08-26T01:00:00Z",
  "reportedAt": "2026-08-26T01:10:30Z",
  "studySec": 600,
  "focusSec": 540,
  "events": []
}
```

- `startedAt` = 세션 식별자. 최종 제출의 `startedAt`과 같은 값. `(userId, startedAt)`이
  draft의 멱등 키다. `(userId)` 단독 유니크로 하지 않는 이유: 앱이 죽고 새 세션을
  시작하면 옛 draft(확정 대기)와 새 draft가 잠깐 공존해야 하고, 확정 경로를 스케줄러
  하나로 단일화하기 위함.
- `events`는 기존 `StatusEventRequest` 재사용. 진행 중인 비집중 이벤트는 `reportedAt`
  에서 닫아서 보낸다(다음 스냅샷이 통째로 덮어쓰므로 자연 갱신).
- **검증은 기존 create 규칙을 예외 없이 재사용** — `endedAt := reportedAt`으로 두고
  시각 순서·24시간 초과·미래 5분 허용·studySec/focusSec 범위·이벤트 겹침/구간을 전부
  적용한다(ADR-0009로 최소 길이 규칙은 이미 없음). 이로써 draft는 항상 "확정 가능한
  상태"가 보장되어 스케줄러가 검증 실패를 만날 일이 없다.
- **역순 도착 가드**: 저장된 draft보다 `reportedAt`이 과거인 스냅샷은 조용히 무시.
- 응답: 성공·무시 공통 `204 No Content`. 검증 위반 400(`ErrorResponse`), 유저 없음 404.
- `userId`는 기존 create와 동일하게 본문으로 받는다(현 SecurityConfig permitAll 계약).
  인증 계약 정렬(BY-383) 시 두 API가 함께 이동한다.

## 스키마: `V12__active_study_session.sql`

```sql
CREATE TABLE active_study_session (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    reported_at  TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    study_sec    INT         NOT NULL,
    focus_sec    INT         NOT NULL,
    events       JSONB       NOT NULL DEFAULT '[]',
    CONSTRAINT uq_active_study_session_user_started UNIQUE (user_id, started_at)
);
ALTER TABLE active_study_session ADD FOREIGN KEY (user_id)
    REFERENCES users (id) DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE study_session ADD COLUMN auto_finalized BOOLEAN NOT NULL DEFAULT false;
```

**`reported_at`(클라 시계)과 `last_seen_at`(서버 시계)의 분리가 핵심이다.**
무응답 판정을 클라 시계로 하면 시계가 느린 유저의 공부 중 세션이 즉시 확정된다.
"죽었나?"는 서버가 매 UPSERT마다 찍는 `last_seen_at`으로, "언제까지 공부했나?"는
`reported_at`으로 판정한다.

- 이벤트는 jsonb 통째 덮어쓰기 — draft는 `status_event` 자식 행을 만들지 않는다.
  확정 시 역직렬화해 create 경로로 넘기면 그때 실제 행이 생긴다.
- 엔티티 `ActiveStudySession`: `events`는 `@JdbcTypeCode(SqlTypes.JSON)` `String`
  필드 + 서비스 계층 Jackson 3 직렬화가 기본 경로. 구현 시 타입드 매핑(List 직접
  매핑)을 먼저 시도하고 FormatMapper 호환이 안 되면 String으로 간다.
- 스케줄러 조회는 `WHERE last_seen_at < now() - 5분` — 테이블 크기가 동시 공부
  세션 수라 인덱스 불필요.
- 스냅샷 저장은 PostgreSQL 네이티브 `INSERT ... ON CONFLICT (user_id, started_at)
  DO UPDATE ... WHERE 기존 reported_at < 새 reported_at` 한 문장 — 동시 첫 스냅샷
  레이스와 역순 도착 가드를 원자적으로 함께 해결한다(레이스 패자·과거 스냅샷은
  조용히 0행 갱신). 조회-후-갱신 방식은 유니크 충돌 flush 실패가 트랜잭션을
  오염시켜(rollback-only) 레이스 시 204 대신 500이 나가는 문제가 있어 기각.

## 확정 스케줄러와 정합 정책

**`ActiveSessionFinalizeScheduler`** (`studysession/scheduler/`): `fixedDelay` 1분,
`last_seen_at` 5분 경과 draft를 확정한다. 30초 주기 기준 하트비트 10회 연속
유실이어야 하므로 일시 단절 오탐이 사실상 없고, 확정 지연은 최대 유예 5분 + 스캔 1분.

draft마다 독립적으로 처리한다. 확정 메서드 자체에는 트랜잭션을 두지 않는다 —
`create`가 자기 트랜잭션으로 돌아야, 유니크 충돌로 create가 롤백돼도(rollback-only
오염) 후속 draft 정리가 별도 트랜잭션에서 살아남는다(컨트롤러의 재조회 패턴과 동일한
이유):

1. jsonb 이벤트를 역직렬화해 `endedAt := reportedAt`으로 **기존
   `StudySessionService.create`를 자체 트랜잭션으로 호출** — 검증·자정 분할·statDate
   계산 전부 재사용, `auto_finalized = true`로 저장. 성공 시 draft 삭제는 create
   트랜잭션 안에서 함께 일어난다(최종 제출 경로와 동일). 길이 무관 전부
   확정한다(ADR-0009: 저장 무제한, 조회 1분·스트릭 10분 임계값이 노이즈를 거른다).
2. `DuplicateSessionException`(별개 제출 조각과 시각 충돌) 또는 클라 제출본이 이미
   있어 create가 멱등 반환한 경우 → 기록이 이미 있으니 draft만 삭제(별도 트랜잭션).
3. 영구 실패(검증·유저 없음·역직렬화)만 폐기하고, 일시 예외는 Sentry만 남기고
   draft를 보존해 다음 틱에 재시도한다(확정은 멱등 수렴이라 재시도 안전). 하나
   실패해도 나머지 draft는 계속 처리.

**정합 정책 — `auto_finalized` 세션은 잠정 기록이다:**

- 클라 최종 제출 성공 시 같은 `(userId, startedAt)` draft를 같은 트랜잭션에서 삭제.
- create의 중복 감지에서 기존 조각들이 **전부 `auto_finalized`면 삭제 후 새 제출로
  대체**, 하나라도 클라 제출본이면 기존 멱등 동작(저장된 결과 반환) 유지. 길이 비교는
  하지 않는다 — 스냅샷이 누적값이라 나중 도착분이 항상 상위집합이다.
- 자동 확정 후 하트비트가 다시 오면(5분 넘는 단절, 앱 생존) draft가 재생성되고,
  재확정 시 중복 감지 → 대체로 더 완전한 기록으로 수렴한다. 어느 순서로 꼬여도
  최종 기록은 가장 완전한 버전으로 수렴하므로 유예를 늘려 오탐을 없앨 필요가 없다.
- 스케줄러 확정과 클라 최종 제출이 동시에 달리면 유니크 제약이 한쪽을 막는다.
  스케줄러가 지면 2번 경로로 draft 삭제, 제출이 지면 대체 로직이 재시도. 새 락 불필요.
- **시계 역행 가드는 두지 않는다**: 클라 시계가 뒤로 점프하면 대체본이 짧아질 수
  있으나, 성립 조건이 3중으로 희귀하고(확정~재개 사이 역행 + 벽시계 기반 측정 +
  최근 이벤트 없음 — 나머지는 기존 검증이 400으로 거절), 가드를 최종 제출에도
  적용하면 클라 원천 신뢰 모델(ADR-0003)을 뒤집는다. 승인된 결정(2026-08-27).

## 테스트 전략

- **하트비트 API 통합테스트** (Testcontainers + MockMvcTester): 첫 스냅샷 생성 /
  덮어쓰기(행 1개 유지) / 역순 도착 무시 / 검증 위반별 400 / 유저 없음 404.
- **확정 스케줄러**: 5분 무응답 → 세션 저장(+jsonb 이벤트가 `status_event` 행으로
  복원) + draft 삭제 / 자정 걸친 draft 2조각 분할 / 이미 제출된 세션의 잔여 draft는
  삭제만 / 신선한 draft 미처리 / 하나 실패해도 나머지 진행. 시간 제어는 `Clock` 주입.
- **정합 로직**: auto_finalized + 늦은 최종 제출 → 대체 / 클라 제출본 + 재제출 →
  기존 멱등 유지 / 최종 제출 시 draft 삭제 / 자동 확정 후 하트비트 재도착 → 재확정
  시 대체.

## 문서 정리 (같은 티켓, 별개 커밋)

- **ADR-0014** "진행중 세션 스냅샷과 서버 자동 확정" — ADR-0003 부분 대체를 명시.
- **`StudySessionController` Swagger 정리**: ADR-0009로 삭제된 "10분 미만 400 거절"
  문구가 남아 있다(35~56행 설명, 92행 예시). 코드와 어긋난 문서라 이번에 함께 정리.
