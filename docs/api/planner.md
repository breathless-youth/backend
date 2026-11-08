# 날짜별 플래너 API (BY-1162)

모든 경로는 토큰 인증과 `API-Version: 1`을 사용한다. 실제 요청·응답·오류 예시는 Swagger `/v3/api-docs`의 `Planner`, `PlannerTask`, `PlannerCarry`, `PlannerGoal`, `PlannerRecord`, `PersonalRecordStats` 태그에 있다.

하루는 기기 IANA `timeZone`(생략/잘못된 값이면 저장된 시간대)의 현지 05:00부터 다음 날짜 05:00이다. 조회는 시간대 설정을 바꾸지 않는다. 과거/오늘/미래도 05시로 구분한다. 날짜 조회와 기간의 양 끝 날짜는 포함하며 최대 366일이다. 지원 날짜는 0001-01-01~9999-12-31이며 그 밖은 400이다.

| 경로 | 용도 |
|---|---|
| `GET /api/planner/days/{date}` | 그날 계획·목표·수정된 실제 기록·과목별 시간 |
| `GET /api/planner?from=&to=` | 날짜별 상세 배열, 미래 포함 |
| `GET /api/planner/summary?from=&to=` | 주간/달력용 날짜·할 일 수·완료 수·순공·공부/계획 여부 |
| `PUT /api/planner/days/{date}/subjects/{subjectId}` | 그날 과목 선택 (멱등) |
| `PUT /api/planner/days/{date}/subjects/order` | `{ "subjectIds": [3, 8] }` 순서 저장 |
| `DELETE /api/planner/days/{date}/subjects/{subjectId}` | 그날 과목과 할 일 제거, 반복은 그 회차만 |
| `GET /api/planner/tasks?from=&to=` | 일반/반복 회차 배열 |
| `POST /api/planner/tasks` | `{ "subjectId":3, "date":"2026-11-06", "name":"문제 풀기", "repeat":null }` |
| `PATCH /api/planner/tasks/{key}` | name/done 변경, scope THIS(기본)/FUTURE |
| `DELETE /api/planner/tasks/{key}?scope=THIS` | 이 회차 또는 앞으로 모두 삭제 |
| `PUT /api/planner/tasks/{key}/date` | `{ "date":"2026-11-07" }` 이 회차 이동 |
| `GET /api/planner/tasks/{key}/repeat` | 반복 편집 초기값: 계열·규칙 시작일·요일·종료일 |
| `PUT /api/planner/tasks/{key}/repeat` | `{ "weekdays":["MONDAY","FRIDAY"], "endDate":null }`, []는 안 함 |
| `POST /api/planner/tasks/{key}/materialize` | 세션의 completedTaskIds에 넣을 taskId 준비, 멱등 |
| `GET /api/planner/carryover` | 이전 미완료 후보 (date로 묶어 표시) |
| `POST /api/planner/carryover` | `{ "offeredKeys":["t:12"], "selectedKeys":[] }` |
| `GET/PUT/DELETE /api/planner/goals/default` | 기본 목표, PUT `{ "goalSec":14400 }` |
| `GET/PUT/DELETE /api/planner/goals/{date}` | 개별 목표, GET은 적용/개별/기본 값 구분 |
| `POST /api/planner/records/batch` | 작업별 CREATE/UPDATE/DELETE와 부분 성공 결과 |
| `GET /api/planner/records/stats?date=&timeZone=` | 자정 기준 개인 기록 목록·일간 합계 |
| `GET /api/planner/records/stats/period?from=&to=&compareFrom=&compareTo=&timeZone=` | 개인 수정본의 주·월·비교 기간 일별 합계 |
| `GET /api/planner/records/stats/rhythm?timeZone=` | 개인 수정본 중 측정 기록의 누적 공부일과 7×24 리듬 |

개인 일간 통계는 `PersonalRecordListResponse`이며 `sessions`, `sessionCount`, `totalStudySec`, `totalFocusSec`, `longestFocusSec`, `focusRate`, `totalEventCounts`, `studiedDatesInMonth`, `subjects`를 제공한다. `sessions`는 `PersonalRecordSummaryResponse` 배열이다. 항목은 `displayKey:string`, `recordKey:string`, `originalSessionId:number|null`, `edited:boolean`, `statDate`, `startedAt/endedAt`, `fullStartedAt/fullEndedAt`, `studySec/focusSec/focusRate`, `eventCounts`, `events`, `subjectSegments`, `completedTasks`, `source`, `submissionStartedAt`을 제공한다. 숫자 `id`는 없으며 원본 없는 개인 직접 입력의 `originalSessionId`는 null이다. 표시 항목은 `displayKey`, 배치 편집은 `recordKey`를 사용한다. `subjectSegments`는 기존 `PlannerRecordSegment` 구조다.

개인 기간과 리듬은 각각 기존 `StudyPeriodStatsResponse`, `StudyRhythmResponse`와 같은 구조를 사용한다. 통계는 실제 초와 기존 60초 제외 기준을 유지하고, 집중률·최장 집중·리듬은 측정 개인 기록만 대상으로 한다. 수정하지 않았거나 과목만 바꾼 원본은 기존 statDate와 초를 유지한다. 시간을 바꾼 원본은 저장된 원본 시간대의 자정으로 나누고, 원본 없는 개인 직접 입력은 요청 timeZone을 사용한다. 기존 개인 수정 행에는 입력 당시 시간대가 없으므로 이를 복원하지 않는다. 원본 stats·홈·랭킹·스트릭의 의미는 바꾸지 않는다.

개인 기록의 `edited`는 과목 유무와 무관하다. 원본은 false, 기존 수정 행을 적용한 기록과 새 개인 직접 입력은 true다. 원본의 `source`는 유지한다. 시간 변경으로 여러 자정 조각이 생기면 완료한 할 일 참조는 첫 표시 조각에 한 번만 싣는다. 이 참조 표시가 원본 할 일의 실제 완료 시각·계획일·완료 개수 또는 세션 연결을 바꾸지는 않는다. 원본 날짜 조각을 유지하는 과목 변경은 원래 조각의 참조를 유지한다.

이전 수동 기록은 시작 변경 시 기존 키의 삭제 표식과 새 `m:<시작>` 키를 저장한다. 기존 수정 행에는 이전 키와의 연결이 없으므로 이미 연결을 잃은 수동 수정본의 원본 ID·원본 시간대·완료 참조를 확실하게 복원할 수 없다. 이 경우 `originalSessionId`는 null, 완료 참조는 빈 배열이며 요청 timeZone으로 자정 분할한다. `edited=true`, 실제 개인 시간과 이전 날짜의 삭제 표식은 그대로 반영한다. `originalSessionId`는 연결을 확인한 실제 원본만 제공하며 추측하거나 가짜 값을 만들지 않는다. 기존 키 변경 규칙과 원본 stats·세션·할 일은 바꾸지 않는다.

과목 생성·이름 변경·전역 삭제는 기존 `/api/subjects`를 쓴다. 전체 과목은 20개, 새 이름은 12자, 새 할 일 이름은 25자다. 날짜·과목별 30개 한도에는 반복 회차가 포함된다. 기존 긴 이름·완료 이력은 자르거나 초기화하지 않는다.

`key`는 일반 할 일 `t:12`, 반복 `r:4:2026-11-06`처럼 안정적이다. 반복의 원래 회차 날짜는 이동 뒤에도 key에 남는다. `taskId=null`인 가상 회차는 실체화 후 세션 제출에 사용한다. 완료 날짜의 집계 기준은 `date`이고, `doneAt`은 늦게 체크한 실제 시각이다.

반복 편집 초기값은 `GET /api/planner/tasks/{key}/repeat`로 읽는다. 응답 예시는 `{"seriesId":4,"startDate":"2026-11-06","weekdays":["MONDAY","WEDNESDAY","FRIDAY"],"endDate":"2026-12-31"}`다. 요일은 월~일 순서이며 종료일은 포함한다. 무기한이면 `endDate=null`이다. 일반 할 일은 `seriesId=null`, `weekdays=[]`, 두 날짜 null이다. 종료된 계열에 남은 개별 예외는 계열 ID를 유지하되 나머지는 같은 ‘안 함’ 값이다. 쓰기와 동일하게 **현재 계획 날짜에 유효한 규칙 버전**을 읽으므로 이동 후 날짜가 규칙 변경 경계를 넘으면 새 버전을 읽는다. 선택한 요일 밖으로 옮긴 회차와 요일 밖 시작일도 기간으로 규칙을 찾는다. 조회는 가상 회차를 저장하지 않는다. 없는/삭제/타인 회차는 404, 잘못된 key는 400이다.

그날 과목의 합집합은 서버가 계산한다. 직접 선택한 과목·할 일이 있는 과목·기록에 남은 과목을 포함한다. `selected`는 직접 선택 여부이며 기록만 있는 삭제 과목도 내려온다. 이 계약은 ‘그날 존재했던 과목’인 BY-1053 `/api/subjects/day`와 다르다.

과거에는 완료·완료 취소와 오늘 이후로의 이동만 허용한다. 미래 완료는 불가하다. 과목 제거 시 실제 기록이 있으면 409이고, 그날의 반복 회차만 지운다. 한도·중복·소유 검증 실패는 전체 트랜잭션을 되돌린다.

가져오기는 전체 원자적이다. 제시한 항목 중 선택하지 않은 것은 원래 날짜에 두고 다시 묻지 않는다. 실패하면 이동과 무시를 모두 취소한다. 선택 항목을 이미 오늘로 옮긴 요청을 재전송해도 중복되지 않는다. 반복은 오늘 예정 회차가 없을 때 최근 미완료 한 개만 제시한다.

목표는 0~86400초를 1초 단위로 저장한다. 명시적인 0은 설정된 값이다. 개별 DELETE는 기본 목표를 다시 상속하고, 기본 DELETE는 기본값을 해제한다. 개별 목표 쓰기는 기본적으로 사용자 현지 05시 기준 미래 날짜만 허용한다. 홈은 기존 날짜별 PUT에 `calendar=HOME`과 기기 시간대 `timeZone`을 전달해 현지 달력상 오늘의 목표를 저장하며 자정 이후에도 같은 날짜로 재시도할 수 있다. 홈과 플래너는 같은 날짜의 목표를 공유한다.

기록 배치는 최대 50개이며 요청 순서대로 독립 트랜잭션으로 처리한다. 각 결과의 success/status/code로 성공과 실패를 구분한다. 응답 유실 때 같은 operationId/본문으로 재전송한다. 확인된 실패를 조건 변경 후 다시 저장할 때는 새 operationId를 사용한다. 다른 기록과 겹침·미래·잘못된 시간·24시간 초과를 거절한다. 측정 묶음은 `c:<원본시작시각>`, 수동은 `m:<묶음시작시각>`이며 원본 자정 조각들을 묶어 편집한다.

새 개인 기록은 원본을 보존하는 수정본이다. 시각 변경은 원본 이벤트의 절대 시각과 새 구간의 겹침을 제외하고, 확장한 시간은 개인 플래너에서 집중으로 센다. 과목만 바꾸면 현재 총공부·순공이 유지된다. 플래너의 총공부·순공·과목별 시간·달력은 이 수정본을 사용한다. **기존 `/api/stats`·`/api/subjects/day`·과목 누적·랭킹은 원본을 계속 읽으므로 새 플래너 집계의 출처로 섞어 쓰지 않는다.** 새 플래너 변경이 랭킹 값을 바꾸지는 않는다.

기록의 `startedAt/endedAt`은 조회 창 안의 표시 구간이고, `fullStartedAt/fullEndedAt`은 **현재 개인 기록 전체 구간**이다. 예를 들어 KST 04:30~06:00 기록은 전날 응답에서 04:30~05:00, 당일 응답에서 05:00~06:00으로 표시되지만 양쪽의 full 필드는 모두 04:30~06:00이다. 원본 자정 분할 조각도 전체 묶음의 범위를 제공한다. 수정본이면 수정된 전체 범위이며 최초 측정 시각을 뜻하지 않는다.

과목만 변경할 때는 UPDATE에서 `startedAt/endedAt`을 모두 생략한다. 두 시각을 현재 full 필드와 정확히 같게 보내도 같은 결과다. 서버는 초·소수초와 날짜별 총공부·순공 배분을 보존한다. 한 시각만 생략하면 항목 400이며 저장하지 않는다. CREATE는 두 시각이 필수이고 분 단위로 내린다. 실제 시간 변경은 **변경한 경계만** 분 단위로 내리고 변경하지 않은 경계의 정밀도를 보존한다. 변경 후 구간은 1분~24시간이어야 한다. 기존 1분 미만 측정 기록도 시각을 그대로 두면 과목만 변경할 수 있다.

```json
{"operations":[{"operationId":"subject-only-001","action":"UPDATE","key":"c:2026-10-03T19:30:12.123456Z","subjectId":8}]}
```

위 요청을 재시도할 때는 operationId와 본문을 그대로 보낸다. 저장 후 날짜별 상세·기간 요약을 다시 읽어 반영된 과목과 순공을 확인한다.

기존 수동 기록은 다른 기록을 피한 여러 조각이 한 묶음일 수 있다. 시각이 그대로인 과목 변경은 새 점유 시간을 만들지 않으므로 묶음 사이의 다른 기록 때문에 거절하지 않는다. 과목 구간도 원래 조각의 경계를 유지해 빈 구간을 칠하지 않는다. 실제 시각 변경에는 기존 겹침 검증을 적용한다.

## 이전과 출시 확인

V53은 기존 id·done_at·deleted_at·세션의 task_id를 유지한다. 미완료는 마이그레이션 시점의 계정 시간대 05시 날짜, 완료는 done_at의 같은 기준 날짜다. 해당 과목 선택은 중복 없이 생성한다. Flyway 재기동은 적용된 버전을 다시 실행하지 않는다. 구 앱의 날짜 없는 신규 INSERT도 trigger가 계정 시간대로 날짜를 채운다.

이전 전후 아래 결과를 보관해 비교한다. 별도의 운영 DB 변경은 이 구현 작업에서 실행하지 않았다.

```sql
-- 이전 전후 두 번 실행: 개수와 실제 완료 이력·세션 참조가 같은지 확인한다.
SELECT count(*) AS tasks, count(done_at) AS completed,
       count(deleted_at) AS deleted FROM study_task;
SELECT id, subject_id, done_at, deleted_at FROM study_task ORDER BY id;
SELECT session_id, task_id FROM study_session_task_done ORDER BY session_id, task_id;

-- 이전 후: 날짜 누락·반복 회차 중복은 0행이어야 한다.
SELECT id FROM study_task WHERE planned_date IS NULL;
SELECT series_id, occurrence_date, count(*) FROM study_task
WHERE series_id IS NOT NULL GROUP BY series_id, occurrence_date HAVING count(*) > 1;
```

실제 V52→V55 PostgreSQL 이전과 재기동, 현지 04:59/05:00·DST, 완료/삭제 시각 및 참조 보존은 `PlannerMigrationTest`로 검증한다. FE 타입 생성은 Swagger 계약을 기준으로 하고 FE·앱 연동·전체 공개는 별도 확인 후 진행한다.
