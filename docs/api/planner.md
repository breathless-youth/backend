# 날짜별 플래너 API (BY-1162)

모든 경로는 토큰 인증과 `API-Version: 1`을 사용한다. 실제 요청·응답·오류 예시는 Swagger `/v3/api-docs`의 `Planner`, `PlannerTask`, `PlannerCarry`, `PlannerGoal`, `PlannerRecord` 태그에 있다.

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
| `PUT /api/planner/tasks/{key}/repeat` | `{ "weekdays":["MONDAY","FRIDAY"], "endDate":null }`, []는 안 함 |
| `POST /api/planner/tasks/{key}/materialize` | 세션의 completedTaskIds에 넣을 taskId 준비, 멱등 |
| `GET /api/planner/carryover` | 이전 미완료 후보 (date로 묶어 표시) |
| `POST /api/planner/carryover` | `{ "offeredKeys":["t:12"], "selectedKeys":[] }` |
| `GET/PUT/DELETE /api/planner/goals/default` | 기본 목표, PUT `{ "goalSec":14400 }` |
| `GET/PUT/DELETE /api/planner/goals/{date}` | 개별 목표, GET은 적용/개별/기본 값 구분 |
| `POST /api/planner/records/batch` | 작업별 CREATE/UPDATE/DELETE와 부분 성공 결과 |

과목 생성·이름 변경·전역 삭제는 기존 `/api/subjects`를 쓴다. 전체 과목은 20개, 새 이름은 12자, 새 할 일 이름은 25자다. 날짜·과목별 30개 한도에는 반복 회차가 포함된다. 기존 긴 이름·완료 이력은 자르거나 초기화하지 않는다.

`key`는 일반 할 일 `t:12`, 반복 `r:4:2026-11-06`처럼 안정적이다. 반복의 원래 회차 날짜는 이동 뒤에도 key에 남는다. `taskId=null`인 가상 회차는 실체화 후 세션 제출에 사용한다. 완료 날짜의 집계 기준은 `date`이고, `doneAt`은 늦게 체크한 실제 시각이다.

그날 과목의 합집합은 서버가 계산한다. 직접 선택한 과목·할 일이 있는 과목·기록에 남은 과목을 포함한다. `selected`는 직접 선택 여부이며 기록만 있는 삭제 과목도 내려온다. 이 계약은 ‘그날 존재했던 과목’인 BY-1053 `/api/subjects/day`와 다르다.

과거에는 완료·완료 취소와 오늘 이후로의 이동만 허용한다. 미래 완료는 불가하다. 과목 제거 시 실제 기록이 있으면 409이고, 그날의 반복 회차만 지운다. 한도·중복·소유 검증 실패는 전체 트랜잭션을 되돌린다.

가져오기는 전체 원자적이다. 제시한 항목 중 선택하지 않은 것은 원래 날짜에 두고 다시 묻지 않는다. 실패하면 이동과 무시를 모두 취소한다. 선택 항목을 이미 오늘로 옮긴 요청을 재전송해도 중복되지 않는다. 반복은 오늘 예정 회차가 없을 때 최근 미완료 한 개만 제시한다.

목표는 초 단위 0~86400, 1800 단위다. 명시적인 0은 설정된 값이다. 개별 DELETE는 기본 목표를 다시 상속하고, 기본 DELETE는 기본값을 해제한다. 개별 목표 쓰기는 미래 날짜만 허용한다.

기록 배치는 최대 50개이며 요청 순서대로 독립 트랜잭션으로 처리한다. 각 결과의 success/status/code로 성공과 실패를 구분한다. 응답 유실 때 같은 operationId/본문으로 재전송한다. 확인된 실패를 조건 변경 후 다시 저장할 때는 새 operationId를 사용한다. 다른 기록과 겹침·미래·잘못된 시간·24시간 초과를 거절한다. 측정 묶음은 `c:<원본시작시각>`, 수동은 `m:<묶음시작시각>`이며 원본 자정 조각들을 묶어 편집한다.

새 개인 기록은 원본을 보존하는 수정본이다. 시각 변경은 원본 이벤트의 절대 시각과 새 구간의 겹침을 제외하고, 확장한 시간은 개인 플래너에서 집중으로 센다. 과목만 바꾸면 현재 총공부·순공이 유지된다. 플래너의 총공부·순공·과목별 시간·달력은 이 수정본을 사용한다. **기존 `/api/stats`·`/api/subjects/day`·과목 누적·랭킹은 원본을 계속 읽으므로 새 플래너 집계의 출처로 섞어 쓰지 않는다.** 새 플래너 변경이 랭킹 값을 바꾸지는 않는다.

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
