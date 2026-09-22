# 기록 탭 v2 — 과목 구간 계약과 일간 조회 확장 (BY-556)

- 작성일: 2026-09-22
- 스토리: [BY-556](https://breathless-youth.atlassian.net/browse/BY-556) 사용자는 기록 탭에서 공부 추이 및 상세를 주간/월간별로 확인할 수 있어야 한다
- 서브태스크: [BE] BY-733 과목 구간 계약 → [BE] BY-734 일간 조회 확장 → [FE] BY-735 계약 교체·API 연동 → 화면 BY-565~568
- 프로토타입: `기록창-standalone.html` (2026-09-22 인터뷰 기준)
- 상태: 설계 확정 → BY-733 구현 계획(writing-plans)으로 이관
- 선행 설계: ADR-0021(과목·할 일·세션 과목 시간), ADR-0022(과목 순서·색·완료 할 일), `2026-08-28-by454-record-query-api-design.md`(기록창 v1 조회)

## 1. 배경

기록 탭 v2 프로토타입은 v1(주간/월간 토글 + 날짜 상세 + 세션 바텀시트)에 두 가지를 더한다.

- **24시간 타임테이블**: 날짜 상세와 세션 바텀시트에 2분 단위 칸 720개를 두고, 칸마다 그때 공부한 **과목 색** / **휴식(회색)** / 빈칸을 칠한다.
- **과목·할 일 표시**: 날짜 상세에 과목별 시간 목록, 바텀시트에 과목별 시간과 그 세션에서 **완료한 할 일 이름**을 보여준다.

기존 서버는 세션당 과목별 **합계**(`study_session_subject_time`, ADR-0021)만 저장하고 "언제 어느 과목을 했는지"는 모른다. 프로토타입은 이 정보가 없어 합계 비율로 세션을 앞뒤로 쪼개 칠한 목업이다. 또 세션 응답이 과목·할 일을 **id로만** 주고, 과목 목록 API는 살아있는 과목만, 할 일 목록은 "미완료 + 오늘 완료"만 주므로 지운 과목·어제 완료한 할 일의 이름을 앱이 어떤 API로도 못 찾는다.

골든타임(시간대별 집중 분포, "나의 리듬")은 이번 범위에서 제외했다.

## 2. 결정

인터뷰(2026-09-22)에서 확정한 것.

1. **타임테이블의 과목 색은 실제 전환 시각을 따른다.** 비율 근사(프로토타입 방식)는 화면이 실제와 다른 순서를 보여주므로 기각. 집중/휴식 두 색만 칠하는 안도 기각 — 이 화면의 핵심이 "하루를 어떻게 썼나"다.
2. **과목 시간은 합계가 아니라 구간으로 받는다.** 세션 제출·스냅샷·복구의 `subjectTimes[]`(과목별 순공·총공부 합계)를 **없애고** `subjectSegments[]`(subjectId, startedAt, endedAt)로 **교체**한다. 비공부 이벤트가 시각만 보내고 길이는 서버가 계산하는 것과 같은 원칙이다. 같은 사실을 두 형태로 받으면 어긋날 수 있다.
3. **과목별 순공·총공부는 서버가 구간과 이벤트로 계산한다.** 앱 타이머가 멈추는 규칙(PAUSE는 둘 다, 나머지 이벤트는 순공만)과 같은 계산이라 앱이 보내던 값과 같은 결과가 나온다. 세션 단위 `studySec`·`focusSec`는 지금처럼 앱 값을 믿고(ADR-0006·0008), 구간 합을 세션 값에 맞추는 검증은 두지 않는다(ADR-0021 §4 유지).
4. **병행 없이 바로 교체한다.** `subjectTimes`를 보내는 앱(과목 시트, frontend#179)은 배포 전이고 운영에 그 데이터가 없다. 구 앱(API-Version 1)은 이 필드를 애초에 몰라 영향이 없다.
5. **일간 조회 한 번으로 날짜 상세·타임테이블·바텀시트를 다 그린다.** `GET /api/stats?date=`의 세션마다 이벤트·구간·완료 할 일을 싣고, 응답에 과목 이름·색을 한 번 붙인다. 하루 세션은 몇 건이라 응답 크기는 문제가 아니다. 지운 과목·할 일의 이름도 서버가 붙인다.
6. **표현은 앱이 정한다.** 농도 임계값, 평균 집중(단순/가중), PAUSE 칸 색, 과목별 시간에 순공/총공부 중 무엇을 쓸지, 구간 밖(과목 미선택) 칸 색. 서버는 원시값을 준다.

## 3. 화면 → API 매핑

| 화면 | 요소 | 출처 | 변경 |
|---|---|---|---|
| 공통 헤더 | 주·월 순공시간, 지난 기간 대비 증감 | `GET /api/stats/period` dailyList·compareDailyList 합 | 없음 |
| 일간 탭 | 월 달력 셀(h:mm, 농도 3단계, 미래 흐림) | `period` from=1일 to=말일 | 없음 |
| 날짜 상세 | 제목·세션 N회·순공·총 공부·평균 집중 | `GET /api/stats?date=` | 없음 |
| 날짜 상세 | 과목별 시간 목록(이름·색·시간) | `stats?date=`.sessions[].subjectSegments를 과목으로 묶어 합산 + `subjects[]` | **BY-733·734** |
| 날짜 상세 | 24시간 타임테이블(과목 색·휴식) | `stats?date=`.sessions[].subjectSegments·events | **BY-733·734** |
| 날짜 상세 | 세션 목록 행 | `stats?date=`.sessions[] | 없음 |
| 바텀시트 | 시간 범위·총·순공·휴식·집중률 | 같은 응답의 그 세션 | 없음 |
| 바텀시트 | 과목별 시간·완료한 할 일 이름 | 같은 응답의 subjectSegments·completedTasks + `subjects[]` | **BY-733·734** |
| 바텀시트 | 타임테이블 | 같은 응답의 subjectSegments·events | **BY-733·734** |
| 주간 탭 | 날짜 스트립·막대 | `period` from=월 to=일 | 없음 |
| 주간 탭 | 선택일 카드(순공·세션 N회·집중 %) | `stats?date=` 탭마다 1회 | 없음 |
| 주간 탭 | 이 달 최고 기록·이 달 공부 N일 | `period` 월 호출을 앱이 계산 | 없음 |

`GET /api/study-sessions/{id}`(v1 바텀시트용)는 그대로 두고 응답 모양만 일간 목록과 맞춘다. 골든타임은 범위 밖.

## 4. BY-733 — 과목 구간 계약

### 4.1 계약 (API-Version 2)

`POST /api/study-sessions`, `PUT /api/study-sessions/active`, `GET /api/study-sessions/active`에서 `subjectTimes[]`를 제거하고 `subjectSegments[]`를 넣는다. 선택 필드라 없거나 `[]`이면 지금과 같다.

```jsonc
// 요청 원소 — SubjectSegmentRequest (제출·스냅샷 공용, 복구 응답도 같은 모양)
{ "subjectId": 3, "startedAt": "2026-09-22T00:12:00Z", "endedAt": "2026-09-22T00:41:00Z" }

// 응답 원소 — SubjectSegmentResponse (StudySessionResponse.subjectSegments[], 시작 오름차순)
{ "subjectId": 3, "startedAt": "...", "endedAt": "...", "studySec": 1740, "focusSec": 1620 }
```

**검증**(`StudySessionValidator.validateSubjectSegments`, 이벤트와 같은 규칙, 위반 400)

- `endedAt > startedAt` (0초 구간 거절)
- 세션 구간 안. 스냅샷은 `[startedAt, reportedAt]` 안 — 진행 중 구간은 앱이 reportedAt에서 닫아 보낸다
- 서로 겹치지 않음. 끝과 시작이 맞닿는 것은 허용. 순서는 뒤섞여 와도 되고 서버가 시작 시각으로 정렬한다
- `subjectId`는 토큰 유저의 과목. 세션 중 지운 과목은 허용(ADR-0021 §1). 컨트롤러가 `StudySubjectService.assertOwned(userId, Set<Long>)`를 먼저 부른다(ADR-0021 §6 배치 그대로)
- 개수 상한은 두지 않는다(이벤트와 같음)
- 구간 사이 빈 시간은 "과목 미선택"이다. 앱이 그 칸을 기본 집중색으로 칠한다

### 4.2 계산

구간마다 서버가 계산한다.

- `studySec = 구간 길이 − 구간과 겹치는 PAUSE 이벤트 길이 합`
- `focusSec = 구간 길이 − 구간과 겹치는 모든 이벤트 길이 합`

이벤트끼리는 겹치지 않으므로(검증됨) 겹침 합이 곧 정확한 값이다. 길이는 이벤트와 같이 `Duration.toSeconds()` 절삭이며, `floor(a)+floor(b) ≤ floor(a+b)`라 결과가 음수가 되지 않는다(안전하게 0 하한).

세션 단위 `studySec`·`focusSec`는 앱 값 그대로다. 구간 `studySec` 합 ≤ 세션 `studySec` 같은 교차 검증은 하지 않는다.

### 4.3 자정 분할

`StudySessionSplitter`가 이벤트를 `[조각 시작, 조각 끝)`으로 잘라내는 `clip`과 같은 방식으로 구간을 자른다. 0초 조각은 버린다. 잘린 구간마다 **그 조각의 이벤트**로 4.2를 다시 계산한다. 비례 배분(`splitSubjectTimes`)은 사라진다.

자동 확정(`ActiveStudySessionService.finalizeDraft` → `create`)도 같은 경로를 탄다.

### 4.4 저장 (V20)

```sql
DROP TABLE study_session_subject_time;

CREATE TABLE study_session_subject_segment (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    subject_id BIGINT      NOT NULL REFERENCES study_subject (id),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at   TIMESTAMPTZ NOT NULL,
    study_sec  INT         NOT NULL,   -- 서버가 계산한 파생값
    focus_sec  INT         NOT NULL
);
CREATE INDEX idx_study_session_subject_segment_session ON study_session_subject_segment (session_id);
CREATE INDEX idx_study_session_subject_segment_subject ON study_session_subject_segment (subject_id);

ALTER TABLE active_study_session DROP COLUMN subject_times;
ALTER TABLE active_study_session ADD COLUMN subject_segments JSONB NOT NULL DEFAULT '[]';
```

- 배포 전이라 데이터 이관은 없다. dev DB의 합계 행은 사라지고 시더가 다시 심는다.
- 구간 행에 파생값을 같이 두는 이유: 과목 누적(`GET /api/subjects`의 studySec·focusSec)이 지금처럼 `subject_id` 합산 한 문장이어야 한다(ADR-0021 §3의 근거 유지). 같은 트랜잭션에서 같은 입력으로 쓰고 이후 바뀌지 않으므로 ADR-0007이 걱정한 동기화 버그 부류가 아니다.
- 엔티티 `StudySessionSubjectSegment`는 `StudySessionSubjectTime`을 대체한다. `StudySession.subjectSegments`는 이벤트와 같은 cascade 자식 컬렉션이고 `@OrderBy("startedAt ASC")`다.
- 리포지토리 `StudySessionSubjectSegmentRepository.sumBySubjectIds` → 기존 `SubjectTimeSum` 프로젝션 그대로.

### 4.5 스냅샷·복구

- `ActiveSessionSnapshotRequest.subjectSegments`(선택, `@Valid`) — `subjectSegmentsOrEmpty()`.
- `ActiveStudySession.subjectSegments`(jsonb 문자열), `ActiveSnapshotBuffer`·`ActiveStudySessionBatchRepository`가 `subject_segments`로 직렬화·UPSERT.
- `ActiveSessionSnapshotResponse.subjectSegments: List<SubjectSegmentRequest>` — 요청과 같은 모양이라 앱이 마지막 구간의 과목으로 선택 상태를 복원한다.
- `finalizeDraft`가 파싱한 구간을 `StudySessionCreateRequest`에 실어 `create`한다.

### 4.6 지우는 것

`SubjectTimeRequest`·`SubjectTimeResponse`·`StudySessionSubjectTime`·`StudySessionSubjectTimeRepository`, `validateSubjectTimes`, `splitSubjectTimes`, `SessionAttachments.subjectTimes` → `subjectSegments`. `LegacyStudySessionCreateRequest`·`LegacyActiveSessionSnapshotRequest.toRequest()`는 null을 넘긴다(구 앱은 과목 시트가 없다).

### 4.7 dev 시더

`DevDataSeeder`가 데모 유저에 과목 3개를 만들고(없을 때만), 큐레이션 세션에 구간을 실어 제출한다. dev 서버 기록 탭에 과목 색이 보이게 하려는 것이다. 랜덤 세션은 그대로 둔다.

### 4.8 테스트

- 단위: `StudySessionValidator` 구간 규칙(0초·세션 밖·겹침), `StudySessionSplitter` 자정 절단(안 넘음·넘음·경계 정확히), 파생 계산(PAUSE만·다른 이벤트·겹침 없음·이벤트가 구간을 완전히 덮음). `SubjectTimeSplitTest`를 `SubjectSegmentSplitTest`로 대체.
- API: `StudySessionSubjectTimeApiTest` → `StudySessionSubjectSegmentApiTest` — 제출 저장·응답 정렬, 없이 제출, 남의 과목 400, 겹침 400, 자정 분할 두 조각, 스냅샷→복구 왕복, 자동 확정본에 구간 유지, 과목 누적 합산(`GET /api/subjects`).
- 기존 `StudySubjectServiceTest`·`StudySubjectApiTest`의 합계 참조를 구간 테이블로 갱신.

### 4.9 문서

- ADR-0023 "과목 시간을 구간으로 받고 서버가 계산한다" — ADR-0021 §3·§4를 갱신 표시.
- `docs/erd.dbml`: `study_session_subject_time` → `study_session_subject_segment`, `active_study_session.subject_segments`.
- Swagger 설명(`StudySessionController`·`ActiveStudySessionController`·`StudySubjectController`의 `subjectTimes` 언급).

## 5. BY-734 — 일간 조회 확장

### 5.1 응답

`GET /api/stats?date=`(`StudySessionListResponse`)와 `GET /api/study-sessions/{id}`·`POST /api/study-sessions`(`StudySessionResponse`)의 모양을 맞춘다. 구 앱(v1) 컨트롤러가 같은 DTO를 쓰므로 **구 앱이 읽는 필드는 남기고 추가만** 한다. `subjectTimes`·`completedTaskIds`는 구 앱 출시 뒤에 생긴 필드라 교체해도 된다.

```jsonc
// StudySessionListResponse (기존 필드 유지 + 추가)
{
  "sessions": [ {
      // 기존 StudySessionSummaryResponse 필드 (id, statDate, startedAt, endedAt, studySec, focusSec, focusRate, eventCounts)
      "events": [ { "status": "PHONE", "startedAt": "...", "endedAt": "..." } ],
      "subjectSegments": [ { "subjectId": 3, "startedAt": "...", "endedAt": "...", "studySec": 1740, "focusSec": 1620 } ],
      "completedTasks": [ { "id": 12, "name": "문제집 1장 풀기", "subjectId": 3, "deleted": false } ]
  } ],
  "sessionCount": 2, "totalStudySec": 11340, "totalFocusSec": 9120, "longestFocusSec": 3000,
  "focusRate": 80.4, "totalEventCounts": { "...": 0 }, "studiedDatesInMonth": [ "..." ],
  "subjects": [ { "id": 3, "name": "영어", "colorIndex": 2, "deleted": false } ]   // 그날 세션이 참조한 과목 전부, 지운 것 포함
}

// StudySessionResponse — subjectTimes → subjectSegments, completedTaskIds → completedTasks, subjects 추가
```

### 5.2 조립 (구현 중 갱신)

ADR-0021 §6의 방향(세션 도메인은 과목 엔티티를 모른다, 의존은 subject → studysession)을 지킨다.

- 세션 도메인이 소유한 인터페이스 `studysession.service.SubjectLookupProvider.lookup(Collection<Long> subjectIds, Collection<Long> taskIds)` → `SubjectLookup`을 `StudySubjectService`가 구현한다. **`deleted_at`을 무시**한다 — 이 경로가 지운 과목·어제 완료한 할 일 이름의 유일한 출처다. 완료 할 일의 과목이 구간에 없어도 함께 싣는다. 소유는 세션이 이미 그 유저 것이라 다시 검증하지 않는다.
- `SubjectLookup`·`SubjectRef(id, name, colorIndex, deleted)`·`CompletedTaskResponse(id, name, subjectId, deleted)` 레코드는 `CompletedTask`처럼 **`studysession.dto`에 둔다**.
- `StudySessionService`가 제공자를 주입받아 응답을 만들 때(`create`·`findExistingSubmission`·`findById`·`list`) 세션들이 참조한 id를 모아 **한 번** 조회하고 DTO에 붙인다. 세션 단위테스트는 람다 `(s, t) -> SubjectLookup.EMPTY`로 대신한다.
- 처음 설계한 "컨트롤러 조립"은 기각 — 구 앱 컨트롤러가 v2 컨트롤러 메서드에 위임해 두 곳이 어긋날 수 있고, 제출 응답까지 세 경로가 같은 조립을 반복한다. 구 앱은 같은 응답을 받지만 필드 추가만이라 영향 없다.

### 5.3 테스트·문서

- API: 일간 목록에 구간·이벤트·완료 할 일·`subjects`가 실림, 지운 과목·어제 완료한 할 일 이름이 나옴, 세션 상세·제출 응답 모양 일치, 구 앱 경로 응답에 기존 필드 유지.
- ADR-0023 결과 절, Swagger.

## 6. BY-735 — FE 범위 (참고)

- `packages/types` 갱신, 세션 화면의 과목 선택·전환·해제 시각 기록 → 제출·스냅샷 `subjectSegments`, 복구 응답 마지막 구간으로 선택 복원, 로컬 과목별 시간 표시는 구간에서 파생.
- 일간·기간 조회 훅을 새 응답으로 갱신, `subjects[]` id→이름·색 매핑, 구간·이벤트 → 2분 슬롯 변환 순수 함수 + 테스트. 화면 컴포넌트는 BY-565~568.

## 7. 범위 밖

| 항목 | 처리 |
|---|---|
| 골든타임(시간대별 집중 분포) | 후속 스토리 |
| `period` 응답에 세션 수·집중률 추가 | 안 함 — 주간 카드는 `stats?date=` 1회로 충분 |
| 세션 결과 화면 과목별 카드 | 후속 |
| 과목 색 변경(PATCH), 팔레트 디자인 | 후속 |
| 구 앱(API-Version 1) 과목 지원 | 없음, 기존 응답 필드만 유지 |

## 8. 구현 순서

1. BY-733 (`feature/BY-733-subject-segments`, origin/dev 01ef441 기준): V20 → 엔티티·DTO 교체 → 검증·절단·계산 → 스냅샷·복구 → 과목 누적 → 시더 → ADR-0023·ERD·Swagger. PR → dev 머지 → 티켓 완료.
2. BY-734 (`feature/BY-734-record-day-detail`, BY-733 머지 뒤 분기): lookup → 응답 확장 → 테스트 → 문서.
3. BY-735 (frontend): BE dev 배포 뒤.
