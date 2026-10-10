# 랭킹 집계와 조회 API (BY-828)

- 작성일: 2026-10-10
- 스토리: [BY-819](https://breathless-youth.atlassian.net/browse/BY-819) 사용자는 랭킹을 보면서 성취감을 느낄 수 있어야 한다
- 서브태스크: [BE] BY-828 (이 문서) · 짝 [FE] BY-827
- 명세: AI 위키 `product/specs/BY-819-랭킹.md` (breathless-youth/.ai#23), 피그마 S10, 시안 `assets/BY-819/ranking-flow.html`
- 상태: 설계 확정 → PR ① 구현 계획(writing-plans)으로 이관
- 선행 설계: ADR-0005(자정 분할), ADR-0006·0008(순공·총공부 앱 신뢰), ADR-0007·0009(스트릭 계산·임계값),
  ADR-0014(진행 중 스냅샷·자동 확정), ADR-0023(과목 구간 — 파생값을 세션 자식 테이블에 둔 선례)

## 1. 배경

랭킹 탭(순공·집중률·시간대 랭킹판과 명예의 전당)과 홈·공부 결과 화면의 랭킹 노출, 마감 TOP 3 기록을 서버가 집계해
내려준다. 명세의 화면 규칙은 위키 명세가 원본이고, 이 문서는 서버가 무엇을 어떻게 계산해 어떤 계약으로 주는지를 정한다.

서버에는 이미 확정 세션(`study_session`, 자정 분할 조각 단위)과 진행 중 세션의 30초 스냅샷(`active_study_session`)이 있다.
랭킹은 이 둘 위에서 계산하고, 시간대 판을 위해 세션별 구간 순공만 새로 저장한다.

## 2. 결정 (2026-10-10 인터뷰)

1. **규모 전제는 주간 활성 천~수천 명이다.** 조회 때 SQL로 집계하고 판 단위로 메모리 캐시한다(§6). 점수 테이블 사전
   집계는 기각했다 — 자동 확정본 대체·삭제 때 증감이 어긋나는 파생값 동기화 문제(ADR-0007이 겪은 부류)를 새로 들이고,
   이 규모에선 이득이 없다. 만 명대가 되면 점수 테이블 → Redis 순으로 재검토한다.
2. **시간대 순공은 세션 저장 때 구간별로 나눠 자식 테이블에 둔다**(`study_session_slot`, §5.1). 조회 때 이벤트를
   조인해 계산하는 안은 기각 — 같은 배분 규칙을 SQL(확정 세션)과 Java(진행 중 draft)로 두 번 구현하게 된다.
3. **마감 TOP 3는 마감 1분 뒤 확정하고 이후 바꾸지 않는다.** 확정 세션과 진행 중 draft를 함께 읽는다(§8). 자동 확정은
   draft 값을 그대로 옮길 뿐이라 기다릴 필요가 없고, 1분은 자정 뒤 첫 스냅샷(하트비트 30초 + 버퍼 flush 5초 + 네트워크)이
   들어오는 시간이다. 스냅샷 없이 오프라인으로 공부해 나중에 제출한 세션은 확정된 기록을 바꾸지 않는다.
4. **메달은 시간 판에서 30분 이상이어야 받는다** (2026-10-10 기획 변경). 순공·시간대 판은 마감 때 1~3위여도 값이
   1800초 미만이면 기록하지 않는다. 순위는 당기지 않는다(1위 40분·2위 20분이면 1위만 받는다). 집중률은 참가 조건
   (주 10h·월 30h)이 이미 있어 따로 적용하지 않는다.
5. **마감 모달은 04시 이후에 뜬다.** 00시에 마감된 메달도 그날 04시 심야 마감 확정이 끝날 때까지 "안 본 기록" 조회에서
   보류해, 04시 이후 처음 앱을 열 때 그날 메달을 한 번에 묶어 보여준다. 04시를 걸쳐 앱을 보고 있었다면 FE가 띄우지 않고
   다음 새 진입 때 띄운다.
6. **상위 %는 정수다.** `max(1, ceil(순위 × 100 ÷ 참가자 수))`. 순위와 함께 보면 참가자 수를 추정할 수 있음을 수용했다.
   다음 구간 목표("상위 5%까지 28시간")와 목표 예시("3시간이면 상위 50%")는 고정 구간 1·5·10·20·30·50%를 쓴다.
7. **"지금까지 최고 순위"는 역대 마감 최종 순위 중 최고다.** 마감 때 참가자 전원의 개인 최고를 갱신해 둔다(§8, 사용자당 1행).
8. **첫 접속 추월 팝업의 기준 시각은 FE가 보관해 `since`로 넘긴다.** 서버는 상태 없이 그 시각의 순위를 복원한다(§7.3).
9. **다른 사용자의 `userId`는 내려주지 않는다.** 순차 ID라 사용자 규모가 드러난다. 행 키는 고유한 `nickname`이다.
10. **세션 뒤 오른 종목은 "이번 세션을 뺀 나 vs 지금의 나"를 지금의 다른 사람들 사이에서 비교한다**(§7.3). 이번 세션으로
    처음 순위가 생긴 판은 "오른 것"이 아니라 넣지 않는다.
11. **순공·총공부는 계속 앱 값을 믿는다.** ADR-0006·0008이 "랭킹 도입 때 재검토"로 남긴 유보는 이번에도 유지하고,
    부정 사용 대응은 명세 §8의 추후 과제로 둔다.
12. **작업은 PR 3개로 나눈다** — ① 집계 엔진 + 랭킹판 조회 ② 마감 배치 + 랭킹 기록 ③ 홈 카드·추월·세션 뒤 오른 종목.
    이 문서가 세 PR의 계약 전체를 정하므로 FE는 계약으로 먼저 작업할 수 있다.

## 3. 집계 규칙

### 3.1 랭킹판과 값

| type | period | 값 (단위) | 참가 조건 |
|---|---|---|---|
| `FOCUS_TIME` 순공 | `DAILY`·`WEEKLY`·`MONTHLY` | 기간 순공 합 (초) | 값 > 0 |
| `FOCUS_RATE` 집중률 | `WEEKLY`·`MONTHLY` | 기간 순공 합 ÷ 총공부 합 × 100 (%, 응답은 소수 1자리) | 기간 순공 합 ≥ 주 36000·월 108000 |
| `TIME_SLOT` 시간대 | `DAILY`·`WEEKLY` (+ `slot`) | 기간·구간 순공 합 (초) | 값 > 0 |
| `TOTAL_TIME` 누적 시간 | — | 전체 순공 합 (초) | 값 > 0 |
| `TOTAL_DAYS` 누적 일수 | — | 순공 1분 이상 세션이 있는 날 수 (일) | 값 > 0 |
| `MAX_STREAK` 연속 공부 일수 | — | 스트릭 기준 최장 연속 (일) | 값 > 0 |

- **숫자는 다른 화면과 같은 기준으로 센다.** 기간·누적 합은 순공 1분 미만 조각을 뺀다(기록 탭 `GET /api/stats/period`와 같은 숫자).
  누적 일수는 `GET /api/stats/study-days`, 연속 일수는 `GET /api/stats/streak`의 `maxStreak`(세션 10분, ADR-0009)과 같다.
  모두 오늘(KST)까지만 센다.
- **집중률은 비율 원값으로 정렬**하고 표시만 소수 1자리로 반올림한다.
- **참가자**: `users.status = ACTIVE`이고 참가 조건을 만족한 사용자. 익명(기기 등록) 사용자도 기존 `포메NNNNN` 닉네임으로 참가한다.

### 3.2 기간 경계 (KST)

| period | 범위 | 마감 |
|---|---|---|
| `DAILY` | 그날 00:00 ~ 다음 날 00:00 | 다음 날 00:00 |
| `WEEKLY` | 월요일 00:00 ~ 다음 월요일 00:00 | 다음 월요일 00:00 |
| `MONTHLY` | 1일 00:00 ~ 다음 달 1일 00:00 | 다음 달 1일 00:00 |

- 순공·집중률은 세션 조각의 `stat_date`(자정 분할, ADR-0005)로 기간에 넣는다.
- **시간대 구간**: `DAWN` 04–07 · `MORNING` 07–12 · `AFTERNOON` 12–18 · `EVENING` 18–22 · `NIGHT` 22–04.
  심야만 **시작한 날**에 귀속한다 — D일 22:00 ~ D+1일 04:00이 D일 심야다(`slot_date` = D).
- **심야판만 마감이 4시간 늦다.** 심야 일간판은 다음 날 04:00, 심야 주간판은 다음 월요일 04:00에 마감한다.
  심야판의 지금 기간은 `(지금 − 4시간)`의 날짜로 정한다 — 00:00~04:00 사이의 "지금 구간"은 전날의 심야다.

### 3.3 동점

정렬 키는 `값 내림차순 → 도달 시각 오름차순 → userId 오름차순`이다. 공동 순위는 없다.

| 판 | 도달 시각 |
|---|---|
| 기간 판·누적 시간 | 그 값을 만든 마지막 조각의 종료 시각 (`max(ended_at)`). 진행 중 draft는 마지막 수신 시각(집중 중이면 계산 시각) |
| 누적 일수 | 마지막 공부일에 기준을 처음 넘긴 조각의 종료 시각 |
| 연속 공부 일수 | 그 길이를 처음 찍은 연속 구간의 끝 날, 그날 10분 기준을 처음 넘긴 조각의 종료 시각 |

연속 공부 일수 리스트는 같은 일수끼리 한 줄로 묶는다. 묶음 안의 순서도 위 정렬 키라서, 묶음의 순위는 첫 사람의
개인 순위이고 묶음 안 k번째 사람의 개인 순위는 `묶음 순위 + k − 1`이다.

## 4. 진행 중 세션 반영

순공·집중률·시간대 판은 진행 중 세션을 포함한다. 명예의 전당은 실시간이 아니라 확정 세션만 본다.

- **모든 draft를 읽는다.** 하트비트가 끊긴 draft도 자동 확정되면 같은 값이 되므로 포함한다. 세션 저장과 draft 삭제는
  `StudySessionService.create`의 한 트랜잭션이라 같은 스냅샷에서는 확정 합계와 draft가 겹쳐 잡히지 않는다. 다만 draft
  조각은 10초 캐시를 판들이 나눠 쓰고 확정 합계는 매번 새로 읽어서, 캐시 뒤에 확정된 세션은 두 쪽에 다 들어갈 수 있다.
  이를 막으려고 조각마다 출처 draft의 id(`LivePiece.draftId`)를 달고, 순위표 계산(`StandingsCalculator.compute`·
  `rateTotals`)을 읽기 전용 `REPEATABLE_READ` 트랜잭션 하나에서 한다. 그 스냅샷에서 확정 합계와 아직 확정되지 않은
  draft id를 읽고, 그 draft의 조각만 더한다(ADR-0028). 제출이 이미 확정됐는데 뒤늦은 하트비트로 되살아난 draft도
  뺀다. 같은 `user_id`에 `submission_started_at`이 draft의 `started_at`과 같은 `study_session`이 있는 draft다. 자동 확정본도
  확정된 것으로 친다. 자동 확정 뒤에 같은 세션이 하트비트를 이어 보내면 그 draft는 최종 제출이 자동 확정본을 대체할 때까지
  숨겨지고 이어진 시간이 그동안 반영되지 않는다. 이 과소 집계는 이중 집계보다 낫다고 보고 받아들였다. 두 메서드를 바깥
  트랜잭션 안에서 부르면 격리 수준이 바뀌어 보장이 사라진다.
- **draft는 확정과 같은 분할 로직으로 나눈다.** `reportedAt`을 끝으로 보고 자정 분할 조각과 구간 행을 만든다
  (`StudySessionSplitter` + §5.1의 구간 분할기). 1분 미만 조각 제외도 똑같이 적용한다.
- **집중 중**: `last_seen_at`이 계산 시각에서 60초 이내이고, 마지막 이벤트의 `endedAt`이 `reportedAt`과 같지 않다
  (앱은 진행 중 이벤트를 `reportedAt`에서 닫아 보낸다 — 같으면 지금 PHONE·PAUSE 등 이벤트 중이다).
- **집중 중인 draft는 계산 시각까지 늘려 계산한다.** `Δ = min(계산 시각 − last_seen_at, 60초)`만큼 `reportedAt`·`studySec`·
  `focusSec`을 늘린 가상 draft로 분할한다. 경계(자정·구간)를 넘는 Δ도 분할기가 알맞게 나눈다. 늘린 스냅샷 하나가 집중률
  판을 포함한 모든 판에 들어가 집중률 합계도 이를 쓴다. 집중률 판은 `focusing`을 켜지 않아 FE가 보간하지 않는다. 늘리지
  않았을 때와의 차이는 순공·총공부 각각 최대 60초다.
- 응답의 `asOf`(계산 시각)부터 FE가 `focusing: true`인 값을 1초씩 올려 보간한다.
- 마감된 기간(`offset=-1`)은 값을 마감 시각까지만 올리고 모든 줄의 `focusing`을 끈다. 진행 중 조각 캐시(10초)가
  마감 전 스냅샷이면 그 조각이 마지막 조각이고 집중 중이라, 요청 시각까지 올리면 최종 판이 마감 뒤에도 오른다.

## 5. 데이터 모델

### 5.1 V26 — `study_session_slot` (PR ①)

```sql
CREATE TABLE study_session_slot (
    session_id bigint   NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    slot       varchar  NOT NULL,          -- DAWN·MORNING·AFTERNOON·EVENING·NIGHT
    slot_date  date     NOT NULL,          -- 심야는 시작한 날
    focus_sec  integer  NOT NULL,
    PRIMARY KEY (session_id, slot, slot_date)
);
CREATE INDEX idx_study_session_slot_date ON study_session_slot (slot_date, slot) INCLUDE (session_id, focus_sec);
CREATE INDEX idx_study_session_stat_date ON study_session (stat_date) INCLUDE (user_id, focus_sec, study_sec, ended_at);
```

- **세션 저장 트랜잭션에서 함께 만든다.** 자정 분할 조각마다 구간 경계(04·07·12·18·22시)로 다시 자르고, 조각의
  `focus_sec`을 이벤트 제외 길이(`focusActiveSec`) 비율로 배분한다. 마지막 구간이 나머지를 가져가 합이 조각 순공과 같다.
  `StudySessionSplitter.computeSegmentWeights`를 경계만 바꿔 재사용한다. 0초 구간은 행을 만들지 않는다.
- `StudySession`의 cascade 자식 컬렉션이라 자동 확정본 대체·삭제 때 함께 움직인다(과목 구간과 같은 방식, ADR-0023 §6).
- **백필**: 시간대판은 일·주뿐이라 이번 주(전주 일요일 심야 포함) 세션만 있으면 된다. 기동 때 `stat_date ≥ 이번 주 월요일 − 1일`이고
  구간 행이 없는 세션을 채우는 멱등 러너를 둔다. 출시 뒤에는 모든 새 세션에 행이 있어 조회 한 번으로 끝난다.
- `stat_date` 인덱스는 전체 사용자의 기간 집계(지금은 `(user_id, stat_date)`뿐)를 위한 것이다.

### 5.2 V27 — 마감 기록 (PR ②)

```sql
CREATE TABLE ranking_close (
    board_key    varchar     NOT NULL,     -- 예: FOCUS_TIME:WEEKLY, TIME_SLOT:DAILY:NIGHT
    period_start date        NOT NULL,
    closes_at    timestamptz NOT NULL,     -- 기간 마감 시각
    closed_at    timestamptz NOT NULL,     -- 확정한 시각
    skipped      boolean     NOT NULL,     -- 따라잡기 창(1시간)을 넘겨 건너뜀
    PRIMARY KEY (board_key, period_start)
);
CREATE TABLE ranking_record (
    id           bigserial   PRIMARY KEY,
    user_id      bigint      NOT NULL,
    board_key    varchar     NOT NULL,
    period_start date        NOT NULL,
    closes_at    timestamptz NOT NULL,
    rank         smallint    NOT NULL,     -- 1·2·3
    value        numeric(12, 1) NOT NULL,  -- 초 또는 집중률(%)
    seen_at      timestamptz,
    UNIQUE (board_key, period_start, rank)
);
CREATE INDEX idx_ranking_record_user ON ranking_record (user_id, closes_at DESC);
CREATE TABLE ranking_best (
    user_id      bigint      PRIMARY KEY,
    rank         integer     NOT NULL,
    board_key    varchar     NOT NULL,
    period_start date        NOT NULL
);
```

## 6. 엔진 구조

- **새 `ranking` 도메인**: 컨트롤러, 랭킹판·기록·노출 서비스, 순위표 계산과 캐시, 기간 달력(경계·마감·지금 구간),
  마감 스케줄러, 기록 엔티티·리포지토리.
- **세션 데이터는 `studysession`이 제공하는 조회로만 읽는다** (`metrics`가 `StudySessionMetricsService`를 쓰는 것과 같은 방식).
  - 기간·구간·누적 집계 SQL(사용자별 값·도달 시각, `users` 조인으로 닉네임·상태): `studysession` 리포지토리의 네이티브 쿼리.
  - draft의 분할 조각·구간 행·집중 중 여부: `ActiveStudySessionService`가 계산 시각을 받아 돌려준다.
  - 구간 분할기와 `TimeSlot` 열거형은 구간 행을 쓰는 `studysession`에 둔다.
  - 의존 방향은 `ranking → studysession` 하나다.
- **순위표(`Standings`)**: 한 판·한 기간의 정렬된 참가자 목록 `(userId, nickname, value, achievedAt, focusing)`.
  - 캐시 키 `(board_key, period_start)`. 기간 판 10초, 명예의 전당·지난 기간 60초. 같은 키 동시 요청은 한 번만 계산한다.
  - draft 분할 결과는 10초 동안 판들이 공유한다. 조각마다 출처 draft의 id를 달고, 계산 때 확정 합계와 같은 스냅샷에서
    읽은 확정되지 않은 draft id에 없는 조각은 뺀다(§4). 그 사이 확정된 세션이 확정 합계와 캐시된 조각에 두 번 잡히지 않는다.
  - 캐시는 태스크 메모리다(지금 `desired_count` 1). 태스크가 늘어도 각자 계산할 뿐 결과는 같다.
- **내 값은 매 요청 DB에서 새로 읽는다.** 캐시된 순위표에서 나를 뺀 목록에 내 `(값, 도달 시각, userId)`를 이분 탐색으로
  끼워 순위·앞뒤를 만든다. 세션을 막 끝낸 직후에도 내 숫자가 캐시 때문에 늦지 않는다.

## 7. API 계약

모두 `API-Version: 1`(ADR-0015 새 경로 규칙), 토큰 필요(`SecurityConfig` 기본 인증 — 변경 없음), 시각은 UTC ISO-8601,
날짜는 KST 기준 `yyyy-MM-dd`. FE는 랭킹 탭을 보는 동안 **15초마다 폴링**한다(스냅샷이 30초 주기라 더 잦아도 새 값이 없다).

### 7.1 PR ① — `GET /api/rankings/board`

| 파라미터 | 값 | 규칙 |
|---|---|---|
| `type` | `FOCUS_TIME`·`FOCUS_RATE`·`TIME_SLOT`·`TOTAL_TIME`·`TOTAL_DAYS`·`MAX_STREAK` | 필수 |
| `period` | `DAILY`·`WEEKLY`·`MONTHLY` | 기간 판은 필수, 명예의 전당은 주면 400. 없는 조합(집중률 일간, 시간대 월간)은 400 |
| `slot` | `DAWN`·`MORNING`·`AFTERNOON`·`EVENING`·`NIGHT` | 시간대만. 생략하면 지금 구간. 다른 판에 주면 400 |
| `offset` | `0`(기본)·`-1` | `-1`은 직전 기간의 최종 결과(5k "어제 최종 결과"). 기간 판만. 지금 DB로 다시 계산하므로 늦은 제출이 있으면 마감 기록과 다를 수 있다 |

```jsonc
{
  "type": "FOCUS_TIME", "period": "WEEKLY", "slot": null,
  "periodStart": "2026-10-05",
  "closesAt": "2026-10-11T15:00:00Z",       // 마감 시각 — 남은 시간은 FE가 asOf 기준으로 계산
  "asOf": "2026-10-10T05:12:30Z",           // 값 기준 시각 — focusing 값 보간 시작점
  "podium": [ { "rank": 1, "nickname": "커피세잔", "value": 98100, "focusing": true, "me": false } ],  // ≤3
  "me": { "rank": 1205, "value": 41200, "focusing": false, "topPercent": 37 },                      // 참가 안 했으면 null
  "around": [ /* 앞 2 · 나 · 뒤 2 — 앞이 부족하면 뒤를 더. me가 null이면 [] */ ],
  "above": { "nickname": "형광펜", "gap": 1320, "focusing": true },    // 바로 위, 1위면 null
  "below": { "nickname": "오늘도출석", "gap": 240, "focusing": true }, // 바로 아래, 꼴찌면 null
  "startNowRank": null                       // me가 null일 때만: 지금 시작하면 N위 (참가자 수 + 1). 집중률 판과 offset=-1은 항상 null
}
```

- `value`·`gap` 단위는 §3.1 — 시간 판 초, 일수 판 일, 집중률 %p(소수 1자리).
- 추월(▼N)·"따라오는 중"·"차이가 벌어짐"은 FE가 직전 폴링과 비교해 판단한다. 서버는 상태를 들고 있지 않다.

**판별 추가 필드**

- `FOCUS_RATE`
  - `eligibility: { eligible, focusSec, requiredFocusSec, focusRate, expectedRank }` — 기간에 세션이 없으면 null.
    미달이면 `me`는 null, `expectedRank`는 지금 집중률로 참가하면 받을 순위, `around`는 그 자리 주변(FE가 흐리게 잠근다).
  - `above.catchUpFocusSec` — 총공부 시간이 같을 때 순공을 몇 초 더 하면 바로 위를 넘는지. "하루 1분만 더"는 FE가 남은 일수로 나눈다.
- `TOTAL_TIME`·`TOTAL_DAYS`
  - `nextTier: { percent, remaining, etaDays }` — 1·5·10·20·30·50% 중 내 상위 %보다 좁은 가장 가까운 구간까지 남은 양.
    이미 1% 안이면 null. `etaDays`는 최근 7일 평균 페이스로 나눈 일수(올림), 페이스가 0이면 null.
- `MAX_STREAK`
  - `streak: { maxDays, maxStart, maxEnd, currentDays, currentIsBest, nextRankIfContinue }` — `nextRankIfContinue`는
    `currentIsBest`일 때만, 내일도 공부해 `maxDays + 1`이 되면 받을 순위.
  - `around` 대신 `aroundGroups[]: { days, rank, nickname, othersCount, achievedDate, me, myOrder }` — 일수 묶음 앞 2 · 내 묶음 · 뒤 2.
    다른 묶음은 먼저 달성한 사람의 순위·이름·달성일, 내 묶음은 내 순위·내 이름과 `myOrder`(같은 일수 중 몇 번째).
    `othersCount`는 묶음 인원 − 1.
- `FOCUS_TIME` + `WEEKLY` + `me`가 null
  - `goalExamples[]: { percent, value }` — 지난주 최종 분포에서 구간(1·5·10·20·30·50%)마다 필요한 순공. 지난주 참가자가 없으면 [].

### 7.2 PR ② — 랭킹 기록

- `GET /api/rankings/records/summary` — 메달 버튼·시트(5q·5s)
  ```jsonc
  { "total": 5, "firstCount": 1, "secondCount": 3, "thirdCount": 1,
    "recent": [ /* 기록 항목 최근 6개 */ ],
    "best": null,      // total이 0일 때만: { rank, type, period, slot, periodStart } — 역대 마감 최고, 없으면 null
    "closest": null }  // total이 0일 때만: { type, period, slot, gap } — 메달까지 남은 양이 가장 작은 진행 중 시간 판
  ```
  `closest.gap = max(0, 3위 값 − 내 값, 1800 − 내 값)`(초). 대상은 순공 일·주·월과 시간대 일·주 × 5구간이다.
- `GET /api/rankings/records?rank=&type=&cursor=&size=` — 모두 보기(5r). `rank` 1·2·3, `type` `FOCUS_TIME`·`FOCUS_RATE`·`TIME_SLOT`,
  `(closesAt, id)` 내림차순 커서 페이지(기본 20, 최대 50). 달별 묶음은 FE가 한다. `recent`도 같은 순서의 앞 6개다.
  ```jsonc
  { "items": [ { "id": 31, "type": "TIME_SLOT", "period": "DAILY", "slot": "MORNING", "periodStart": "2026-10-09",
                 "rank": 1, "value": 11060, "closesAt": "2026-10-09T15:00:00Z" } ],
    "nextCursor": "..." }   // 마지막이면 null
  ```
- `GET /api/rankings/records/unseen` — 마감 모달(5t~5w). `{ "total": 5, "records": [ /* 기록 항목 */ ] }`
  - 안 본 기록을 순위 → 일·주·월 → 순공·집중률·시간대 순으로 준다(명세 §4-5). `total`은 누적 메달 수.
  - **04시 보류**: 가장 최근 04:00 마감분(심야 일간, 월요일이면 심야 주간까지)의 확정이 끝난 뒤에야, 그 시각 이전에 마감된
    기록을 준다. 00:00~04:01 사이엔 그날 00시 마감분을 주지 않는다.
- `POST /api/rankings/records/seen` `{ "ids": [31, 32] }` — 모달을 띄운 뒤 본 것으로 표시. 내 기록만 바뀐다(남의 id는 무시). 204.

### 7.3 PR ③ — 노출용

- `GET /api/rankings/home?since=` — 홈 한 줄 카드(5b)와 첫 접속 추월(5a)
  ```jsonc
  { "card": { "rank": 1205, "value": 41200, "above": { "nickname": "커피세잔", "gap": 1320 } },  // 이번 주 기록 없으면 null
    "overtaken": { "fromRank": 1201, "toRank": 1205, "count": 4,
                   "nearest": [ { "nickname": "커피세잔", "gap": 1320,
                                  "studiedFrom": "2026-10-09T20:00:00Z", "studiedFocusSec": 10800 } ] } }  // ≤2
  ```
  - `overtaken`은 `since`를 줬을 때만 계산하고, `since`가 이번 주 월요일 00:00 이전이거나 미래이거나 순위가 내려가지 않았으면 null.
  - `since` 시점의 값은 그때까지 끝난 조각으로 복원하고, 걸쳐 있던 조각(진행 중 draft 포함)은 시간 비율로 나눈다.
  - `count`는 `since`에 내 뒤(또는 미참가)였다가 지금 내 앞인 사람 수. `nearest`는 그중 지금 나와 가장 가까운 2명과,
    그 사람이 `since` 이후 처음 공부를 시작한 시각·`since` 이후 쌓은 순공("새벽 5시부터 3시간 집중").
  - FE 몫: 마지막 포그라운드 시각 보관, 그날 첫 접속 판단, 하루 1회, 마감 모달이 뜬 날 생략.
- `GET /api/rankings/session-gains?startedAt=` — 공부 결과 화면 "랭킹이 올랐어요"(5i). `startedAt`은 제출한 세션의 시작 시각
  (자정 분할 조각 전부를 묶는 `submission_started_at`). 없거나 내 세션이 아니면 404.
  ```jsonc
  { "weekly": { "before": 1205, "after": 1202, "delta": 3 },     // 주간 순공이 오르지 않았으면 null
    "others": [ { "type": "TIME_SLOT", "period": "DAILY", "slot": "EVENING", "before": 310, "after": 301, "delta": 9 } ] }
  ```
  - 대상: 지금 기간의 순공 일·주·월, 집중률 주·월, 이 세션이 지난 시간대 일·주, 누적 시간·누적 일수·연속 일수.
  - `before`는 내 값에서 이 제출의 기여분을 뺀 값, `after`는 지금 내 값을 **같은 다른 사람들** 사이에서 매긴 순위.
    `before`가 있고 `after < before`인 판만 준다. `others`는 명세 칩 순서(순공 일→월, 집중률, 시간대, 명예의 전당).
  - 제출 응답에 넣지 않은 이유: 세션 도메인이 랭킹을 몰라야 의존 방향이 한쪽으로 유지된다.

## 8. 마감 배치 (PR ②)

| 확정 시각 (KST) | 판 |
|---|---|
| 매일 00:01 | 순공 일간, 시간대 일간 4구간(새벽·오전·오후·저녁) |
| 매일 04:01 | 시간대 일간 심야 |
| 월요일 00:01 / 04:01 | 순공·집중률 주간, 시간대 주간 4구간 / 시간대 주간 심야 |
| 1일 00:01 | 순공·집중률 월간 |

- **1분마다 도는 스케줄러**가 "마감 + 1분이 지났고 `ranking_close`가 없는 판"을 찾아 확정한다. 재기동으로 정각을 놓쳐도
  다음 틱에 따라잡는다. 마감 후 1시간이 넘은 판은 `skipped = true`로 표시만 하고 Sentry에 경고한다 — 첫 배포 때 지난
  기간에 메달이 소급되지 않게, 04시 보류가 영영 막히지 않게.
- **판 하나 = 계산 한 번 + 쓰기 트랜잭션 하나**
  1. 쓰기 트랜잭션 밖에서 §6 엔진으로 최종 순위표를 계산한다(캐시 없이, draft는 마감 시각에서 절단, 집중 중 보정 없음).
     엔진이 읽기 전용 `REPEATABLE_READ` 트랜잭션을 스스로 열기 때문에 바깥 쓰기 트랜잭션 안에서 부르면 안 된다(§4).
  2. 아래 2~4를 한 트랜잭션에서 쓴다. 먼저 `ranking_close`를 넣는다. PK 충돌이면 다른 태스크가 이미 확정한 것이라 롤백하고
     끝낸다(배포 중 태스크 2개 대비). 두 태스크가 같은 판을 동시에 계산해도 쓰기는 PK로 하나만 통과한다.
  3. 1~3위 중 메달 조건(시간 판 1800초 이상)을 넘은 사람을 `ranking_record`에 넣는다.
  4. 참가자 전원의 개인 최고를 `ranking_best`에 한 문장 upsert로 갱신한다 — 더 높은 순위일 때만, 같으면 먼저 것을 둔다.
- 실패하면 롤백되어 다음 틱에 재시도한다. 예외는 직접 잡아 Sentry로 올린다(기존 스케줄러와 같은 방식).
- 확정된 기록은 이후 늦은 제출이 와도 바꾸지 않는다.

## 9. 화면 → API

| 화면 (시안 ID) | 출처 |
|---|---|
| 랭킹판 공통·실시간 (5c·5j·5d·5e·5f·5g·5n) | `board` |
| 리셋 직후 (5k) | `board`의 `me: null` + `startNowRank`, 어제 최종 결과는 `board?offset=-1` |
| 처음 들어왔을 때 (5l) | `board`(순공 주간)의 `goalExamples` |
| 집중률 미달 (5m) | `board`(집중률)의 `eligibility` |
| 메달 버튼·시트·기록 없음 (5q·5s) | `records/summary` |
| 모두 보기 (5r) | `records/summary`(요약 카드) + `records` |
| 마감 모달 (5t~5w) | `records/unseen` → `records/seen` |
| 홈 한 줄 카드·첫 접속 팝업 (5b·5a) | `home?since=` |
| 공부 결과 오른 랭킹 (5i) | `session-gains` |

## 10. 오류·경계

- 잘못된 `type`·`period`·`slot`·`offset` 조합, `rank`·`size` 범위 밖, `since` 형식 오류 → 400.
- `session-gains`의 제출이 없거나 남의 것 → 404.
- 참가자가 없는 판: `podium` []·`me` null·`startNowRank` 1. 참가자가 1명이면 `above`·`below` null. 집중률 판은 예외로
  `startNowRank`가 비어 있어도 null이다(주 10시간·월 30시간을 채워야 참가하므로 `eligibility`가 맡는다). 지난 기간(`offset=-1`)도
  모든 판에서 null이다.
- 탈퇴(`DELETE`) 사용자는 집계·마감에서 빠진다. 이미 받은 기록은 남지만 본인만 조회하므로 노출되지 않는다.
- draft JSON을 읽지 못하면 그 draft만 건너뛰고 Sentry에 남긴다(자동 확정 쪽이 따로 폐기 처리한다).

## 11. 테스트

- **단위**: 구간 분할(경계·심야 귀속·이벤트 제외 비례·합 보존·0초 구간), 정렬·동점, 앞뒤 창(1·2위·꼴찌·참가자 1명),
  상위 %·다음 구간·목표 예시, 연속 일수 묶음과 `myOrder`, 집중 중 판정과 Δ 보정, 오른 종목 비교, 추월 복원, 기간 달력(심야 04시·월초·주초).
- **통합(Testcontainers)**: 집계 SQL(1분 제외·탈퇴 제외·기간 경계·도달 시각), draft 반영과 자정 절단, 구간 행 저장과
  자동 확정 대체, 백필 멱등, 마감(중복 확정 방지·메달 30분·순위 안 당김·개인 최고·1시간 창·04시 보류), 기록 seen 소유 검증.
- **API**: `MockMvcTester`로 응답 모양, 400 조합, 404, 인증. 시각은 `Clock` 빈을 고정해 검증한다.

## 12. 작업 단위

| PR | 브랜치 (그 시점 dev에서 분기) | 범위 | 마이그레이션 |
|---|---|---|---|
| ① | `feature/BY-819-ranking` | 구간 테이블·분할기·백필, 집계 쿼리, draft 분할, 순위표·캐시, `board` API, ADR-0028, 이 문서 | V26 |
| ② | `feature/BY-819-ranking-records` | 마감 스케줄러·기록·개인 최고, `records` API 4개 | V27 |
| ③ | `feature/BY-819-ranking-exposure` | `home`, `session-gains` | 없음 |

PR ① 머지 때 BY-827에 FE 담당자 멘션 댓글로 계약(이 문서 §7)을 전달한다.

## 13. FE·기획 전달 사항

1. **마감 모달 04시 규칙**: 서버가 00:00~04:01엔 그날 00시 마감분을 보류한다. 04시를 걸쳐 앱을 보고 있었다면 FE가 띄우지 않고
   다음 새 진입 때 띄운다. 위키 명세 §4-5에 반영 필요.
2. **메달 30분 조건**: 시간 판만, 순위는 당기지 않는다는 해석 — 위키 명세 반영과 기획 확인 필요.
3. **상위 % 공식**: 참가자 100명 미만이면 1위도 2% 이상이 나온다(50명 중 1위 = 상위 2%).
4. **처음 순위가 생긴 판은 "오른 종목"에서 뺐다** — 기획 의도가 다르면 바꾼다.
5. **추월 팝업의 2명은 나를 추월한 사람 중 가장 가까운 2명**이다.
6. 폴링 15초, 행 키는 `nickname`, 심야판 마감은 "04시 마감"으로 적는다.

## 14. 범위 밖

STOMP 푸시·랭킹 푸시 알림, 부정 사용 기준, 화면(BY-827), 보상, 전체 순위표, TOP 3 기록의 프로필·기록 탭 노출.
