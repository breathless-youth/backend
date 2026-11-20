# BY-1322 자율 모드 세션 출처(TIMER) 서버 설계

2026-11-19 작성. [지라 BY-1322](https://breathless-youth.atlassian.net/browse/BY-1322), 브랜치 `feature/BY-1322-timer-session-source`, 스토리 BY-1321.
결정 기록은 ADR-0049, 그룹 쪽 변경은 ADR-0040 끝의 2026-11-19 갱신이다.

## 배경

BY-1321은 카메라 없이 공부하는 모드를 더한다.
제품 이름은 카메라 없는 모드가 "자율 모드", AI 카메라로 재는 모드가 "관리 모드"다. 처음에는 "기본 모드"라고 썼고 2026-11-20에 바꿨다.
서버는 자율 모드 세션을 `StudySessionSource.TIMER`로 저장한다.
지금 enum은 `MEASURED`·`MANUAL` 두 값이고, ADR-0031이 수동 타이머 모드가 생기면 `TIMER`를 더한다고 미리 적어 두었다.

웹은 이미 `POST /api/study-sessions`와 `PUT /api/study-sessions/active` 본문에 선택 필드 `source`(`MEASURED`·`TIMER`)를 보낸다.
지금 서버는 이 필드를 모르는 필드로 무시해서 모두 `MEASURED`로 저장한다.
웹은 `GET /api/study-sessions/active`와 세션 응답에서 `source`를 읽는다.

자율 모드는 공부 시간을 잰 것이 아니라 켜 둔 시간이다.
그래서 경쟁과 보상에는 넣지 않고, 개인 기록과 그룹의 함께 공부한 시간에는 넣는다.

## 결정 표

| 항목 | 결정 |
|---|---|
| `source`가 없을 때 | `MEASURED`로 본다. 구 앱(API-Version 1 컨트롤러)은 지금과 같다. |
| 제출·체크포인트에 `MANUAL` | 400 `VALIDATION_FAILED`다. 수동 기록은 자기 API가 있다. |
| TIMER 본문 검증 | 이벤트·`focusSec` 검증을 더하지 않는다. 랭킹·보상에서 빠지므로 꾸민 값이 남에게 영향을 주지 않는다. |
| 체크포인트와 최종 제출 | 마지막에 받은 값이 이긴다. 체크포인트 UPSERT가 `source`를 덮어쓰고, 최종 제출의 값이 최종이다. |
| 서버 확정(자동 확정·복구) | draft의 `source`를 유지한다. |
| 랭킹 | 확정 집계(이미 `= 'MEASURED'`)와 진행 중 조각 모두에서 TIMER를 뺀다. |
| 포인트·시작 미션·마스코트·상점·연속 공부·명예의 전당 | TIMER를 뺀다. 지금 쿼리가 이미 `= 'MEASURED'`라 코드는 바꾸지 않는다. |
| 그룹 멤버 오늘·이번 주 순공, 공개 그룹 카드 합계·평균, 마지막 공부 시각, 책상 공부 중 상태 | 진행 중과 확정 모두 TIMER를 넣는다. |
| 공개 그룹 목록 정렬 | 관리 모드 순공만 쓴다. 보여 주는 값과 별도로 관리 모드 전용 주간 합계를 계산한다. |
| 기록 탭 일간 조회의 `focusRate`·`longestFocusSec` | `MEASURED`만 본다. |
| 개인 합계(일간 합계, 달력, 과목 누적, 기간 통계) | TIMER를 넣는다. 지금 쿼리에 출처 조건이 없어 코드는 바꾸지 않는다. |
| 멱등 재제출·자동 확정 대체·세션 복구 조회 | `MEASURED`와 TIMER를 함께 찾는다. |
| 플래너 기록 키 | TIMER도 `c:` 접두사를 쓰고, `c:` 조회는 `MEASURED`와 TIMER를 함께 찾는다. |
| 플래너 원본 없는 수정본 숨김 | TIMER를 `MEASURED`처럼 다룬다. |
| 응답 | `source` 설명에 TIMER를 더하고 `ActiveSessionSnapshotResponse`에 `source`를 싣는다. |
| 범위 밖 | 인터뷰 대상, 첫 세션 푸시, 리듬 알림, Slack 일일 리포트, 분석 SQL은 `MEASURED`만 읽는 지금 동작을 유지한다. |

## 조건식 원칙

집계는 넣을 출처를 직접 적는다.
경쟁·보상은 `source = 'MEASURED'`, 그룹 공부 시간은 `source IN ('MEASURED', 'TIMER')`다.
앞으로 출처가 늘어나도 집계에 몰래 섞이지 않게 하려는 것이다.

제출 묶음 조회는 `source <> MANUAL`로 쓴다.
이 조회의 뜻이 "세션 API로 제출한 묶음"이고, 그 묶음은 수동이 아닌 모든 출처이기 때문이다.

## 파일별 변경

### 출처 enum과 엔티티

- `studysession/entity/StudySessionSource.java`는 `TIMER`를 더하고, 주석에 세 값의 뜻과 ADR-0049를 적는다.
- `studysession/entity/StudySession.java`는 `markTimer()`를 더한다.
- `markTimer()`는 `markManual()`처럼 저장 직전 서비스만 부른다.
- `isManual()`은 유지한다.
- `studysession/entity/ActiveStudySession.java`는 `@Enumerated(EnumType.STRING) source` 필드를 더한다.
- 이 필드는 V84의 기본값과 같은 `MEASURED`로 초기화한다.

### 요청 DTO

- `StudySessionCreateRequest`의 마지막 성분으로 `StudySessionSource source`를 더한다.
- Swagger는 `allowableValues = {"MEASURED", "TIMER"}`이고, 없으면 `MEASURED`라고 적는다.
- `sourceOrMeasured()`는 null을 `MEASURED`로 바꾼다.
- `isSessionSource()`는 `@JsonIgnore`·`@Schema(hidden = true)`·`@AssertTrue`로 `MANUAL`을 막는다.
- 이 방식은 `DeleteAccountRequest.isAppleClientGiven()`과 같고, 위반은 400 `VALIDATION_FAILED`다.
- 모르는 값(`"FOO"`)은 지금처럼 역직렬화 실패로 400 `BAD_REQUEST`다.
- `ActiveSessionSnapshotRequest`도 같은 성분과 두 메서드를 더한다.
- 생성자를 부르는 곳은 인자 하나를 더한다.
- 대상은 `DevDataSeeder`, `LegacyStudySessionCreateRequest.toRequest`, `LegacyActiveSessionSnapshotRequest.toRequest`, `ActiveStudySessionService.finalizeDraft`다.
- 테스트 쪽 대상은 `RankingIntegrationTestBase`, `SessionSlotPersistTest`, `StudySessionControllerRetryTest`, `StudySessionIdempotencyServiceTest`다.
- 구 앱 변환과 시더는 null을 넘긴다.
- 구 앱 요청 record에는 `source`를 더하지 않으므로 구 앱이 무엇을 보내도 `MEASURED`다.

### 응답 DTO

- `StudySessionResponse`와 `StudySessionSummaryResponse`의 `source` 설명을 "MEASURED(관리 모드)·TIMER(자율 모드)·MANUAL(직접 입력)"로 바꾼다.
- 두 응답은 이미 `session.getSource()`를 싣고 있어 값은 바뀌지 않는다.
- `ActiveSessionSnapshotResponse`에 마지막 성분 `source`를 더한다.
- 구 앱 `GET /active`도 같은 응답이라 필드가 하나 늘지만, 구 앱은 모르는 필드를 읽지 않는다.
- `planner/record/PersonalRecordSummaryResponse`의 `source`에 같은 설명을 단다.

### 세션 제출

- `StudySessionService.create`는 `validateAndBuildSessions` 뒤에 `request.sourceOrMeasured() == TIMER`이면 조각마다 `markTimer()`를 부른다.
- 자리와 방식은 바로 아래 `markAutoFinalized`와 같다.
- `StudySessionService.list`의 집중률·최대 집중 필터를 `!session.isManual()`에서 `session.getSource() == MEASURED`로 바꾼다.
- 그 위 주석을 "수동 기록과 자율 모드 기록은 합계에만 더한다"로 고친다.
- `StudySessionController.create`의 Swagger 설명에 `source` 한 줄을 더한다.

### 제출 묶음 조회

- `StudySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc`의 조건을 `s.source = MEASURED`에서 `s.source <> MANUAL`로 바꾼다.
- 이 메서드를 쓰는 멱등 반환, 자동 확정본 대체, 레이스 뒤 `findExistingSubmission`, 복구 요약이 한 번에 고쳐진다.
- 지금은 TIMER 묶음이 안 보여서 재전송이 유니크 충돌 뒤 409가 되고, 복구 요약은 빈 목록의 `group.get(0)`에서 500이 난다.
- 주석의 "측정 세션만 본다"를 "수동이 아닌 제출 묶음만 본다"로 고친다.
- `SessionRecoveryService.recover` 2단계는 `findFirstByUserIdAndSourceOrderByStartedAtDesc(userId, MEASURED)`를 `findFirstByUserIdAndSourceNotOrderByStartedAtDesc(userId, MANUAL)`로 바꾼다.
- 기존 메서드는 다른 호출자가 없어 새 이름으로 바꾼다.

### 체크포인트 저장과 확정

- `ActiveStudySessionBatchRepository.UPSERT_SQL`의 INSERT 열과 `DO UPDATE SET`에 `source`를 더한다.
- 덮어쓰기는 기존 `WHERE active_study_session.reported_at < excluded.reported_at` 조건을 따른다.
- 그래서 "마지막 값"은 `reportedAt`이 가장 늦은 스냅샷의 값이다.
- `SnapshotRow`에 `String source`를 더하고, `setValues`가 `observations` 다음인 10번째 인자로 넣는다.
- 클래스 주석의 "SQL은 upsertSnapshot과 동일하다"에 `source` 열은 배치 쪽만 쓴다고 적는다.
- `ActiveStudySessionRepository.upsertSnapshot`은 테스트만 쓰므로 바꾸지 않는다.
- 이 메서드로 만든 draft는 열 기본값 `MEASURED`가 된다.
- `ActiveSnapshotBuffer.flush`와 `ActiveStudySessionService.reportSnapshot`의 durable 경로는 `request.sourceOrMeasured().name()`을 `SnapshotRow`에 넣는다.
- 버퍼는 이미 `reportedAt`이 큰 요청만 남기므로 `source`도 그 요청을 따른다.
- `ActiveStudySessionService.findLatestSnapshot`은 응답에 `draft.getSource()`를 싣는다.
- `ActiveStudySessionService.finalizeDraft`는 `draft.getSource()`를 요청에 넣는다.
- 그래서 자동 확정과 복구 확정 모두 draft의 출처를 유지한다.
- 자동 확정 뒤 부르는 `studyPointService.grantForSubmission`은 `PointRepository.measuredDatesOf`가 `MEASURED`만 보므로 TIMER에는 아무것도 주지 않는다.

### 진행 중 조각과 랭킹

- `LivePiece`에 `StudySessionSource source`를 더한다.
- `ActiveStudySessionService.toLivePieces`가 `draft.getSource()`를 넣는다.
- `LiveDraft`는 조각이 출처를 실으므로 바꾸지 않는다.
- `RankingSource.livePieces`와 `RankingSource.settledPieces`가 `piece.source() == MEASURED`인 조각만 돌려준다.
- 랭킹이 진행 중 조각을 받는 입구는 이 두 메서드뿐이다.
- `StandingsProvider.live()`, `BoardExtras`의 집중률 합계, `RankingCloser`의 마감이 모두 여기를 지난다.
- `StandingsCalculator`는 바꾸지 않는다.
- 확정 집계 4종은 이미 `s.source = 'MEASURED'`다.
- 명예의 전당은 확정 집계만 읽는다.

### 그룹

- `MemberStudyQueries.focusTotals`의 조건을 `s.source IN ('MEASURED', 'TIMER')`로 바꾼다.
- 같은 쿼리에 `SUM(s.focus_sec) FILTER (WHERE s.source = 'MEASURED') AS week_measured_sec`를 더한다.
- `FocusTotals`는 `(todaySec, weekSec, weekMeasuredSec)`가 된다.
- `MemberStudyQueries.lastMeasuredEndedAt`은 `lastStudiedEndedAt`으로 이름을 바꾼다.
- 바깥과 안쪽 서브쿼리의 두 조건을 모두 `IN ('MEASURED', 'TIMER')`로 바꾼다.
- 클래스·메서드 주석의 "측정한 세션만"을 고친다.
- `MemberStudySource.of`는 바뀐 쿼리를 쓴다.
- draft는 출처와 상관없이 이미 상태·마지막 수신에 들어가므로 그 부분은 바꾸지 않는다.
- draft의 순공은 `MEASURED`·`TIMER` 조각만 더한다고 적어서, 확정 집계와 같은 출처 목록을 쓴다.
- `MemberStudySource.weekFocus`는 `Map<Long, WeekSec>`를 돌려준다.
- `WeekSec`는 `record WeekSec(long totalSec, long measuredSec)`이고 `MemberStudySource` 안에 둔다.
- 진행 중 몫을 더하는 `liveFocus`는 넣을 출처 집합을 인자로 받는다.
- 표시용 합은 `MEASURED`·`TIMER`, 정렬용 합은 `MEASURED`만 넘긴다. 확정 집계의 `IN ('MEASURED', 'TIMER')`와 같은 원칙이다.
- `GroupWeeklyFocus.WeekFocus`는 `(totalSec, averageSec, measuredTotalSec)`가 된다.
- `measuredTotalSec`는 응답에 싣지 않는다.
- `GroupWeeklyFocus.compute`는 두 합을 함께 더한다.
- `PublicGroupService.listOrder`는 `totalSec` 대신 `measuredTotalSec`로 정렬한다.
- 카드와 공개 보기의 `averageSec`·`totalSec`는 TIMER를 포함한 값이다.
- 그룹 공부 시작 알림(`GroupStartAlerts`)은 draft를 읽으므로 자율 모드로 시작해도 나간다.
- 그룹 공부 시간에 TIMER를 넣는 결정과 맞으므로 바꾸지 않는다.

### 플래너

- `PlannerRecordViews.key`는 `isManual()`만 `m:`이라 TIMER가 이미 `c:`다.
- 바꾸지 않는다.
- `PlannerRecordService.bundle`의 `c:` 분기는 `findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc`를 부른다.
- 이 조회는 위 변경으로 `MANUAL`이 아닌 묶음을 찾는다.
- `m:` 분기는 지금처럼 `MANUAL`로 찾는다.
- `PersonalRecordProjection`의 원본 없는 수정본 숨김 조건 `edit.source() == MEASURED`를 `edit.source() != MANUAL`로 바꾼다.
- `PersonalRecordPiece.measured()`는 `== MEASURED`라 플래너 일간의 집중률·최대 집중과 플래너 리듬에서 TIMER가 이미 빠진다.
- 결정과 같으므로 바꾸지 않는다.
- `PlannerRecordMutationService`는 원본 출처를 수정본에 복사하므로 TIMER 기록을 고치면 `planner_record_edit.source = 'TIMER'`가 된다.
- 그래서 V84이 이 표의 CHECK를 넓힌다.
- `PlannerRecordViews`·`PlannerRecordPieces`·`PlannerRecordService`의 `== MANUAL`·`!= MANUAL` 분기는 TIMER를 측정 쪽으로 보내므로 바꾸지 않는다.
- `ManualStudySessionService`는 `isManual()`이 아닌 기록에 `MEASURED_RECORD_LOCKED`(409)를 주므로 TIMER도 수동 기록 API로 고칠 수 없다.

### 바꾸지 않는 곳

- `StudySessionRepository`의 `findDistinctStatDates`(연속 공부), `findDistinctMeasuredStatDatesBetween`(연속 공부 기간·랭킹 페이스)는 `MEASURED`만 유지한다.
- `countDistinctStatDates`(누적 공부일)와 `findAllMeasuredWithMinFocusSec`(공부 리듬)도 `MEASURED`만 유지한다.
- 이 둘은 2026-11-19 결정으로 지금 동작을 유지한다(아래 "남은 질문").
- `findInPeriodWithMinFocusSec`, `findDistinctStatDatesBetween`, `findDailyStudyStats`, `findOverlapping`은 출처 조건이 없어 TIMER를 포함한다.
- `findSlotlessSince`(구간 백필)는 `MEASURED`만 본다.
- TIMER 세션은 제출 때 구간 행이 만들어지므로 백필 대상이 아니다.
- 시간대 랭킹은 조인에서 `source = 'MEASURED'`를 거므로 TIMER의 구간 행은 집계되지 않는다.
- `PointRepository`, `HomeMissionService`, `MascotRepository`, `ShopRepository.maxStreakDays`는 `= 'MEASURED'`를 유지한다.
- 인터뷰(`InterviewGroupService`, `existsByUserIdAndSource`, `findLastEndedAt`, `countCompletedSubmissionsEndedAfter`)는 유지한다.
- 푸시(`PushExperimentQueries`, `RhythmReminderQueries`)와 Slack 리포트(`SessionFactQueries`)도 유지한다.
- 계정 병합(`StudySessionUserDataHandler`)은 `UPDATE ... SET user_id`라 `source`를 보존한다.
- 퀘스트와 스크린타임 적립을 계산하는 서버 코드는 없다.

## 마이그레이션 V84

파일은 `src/main/resources/db/migration/V84__timer_session_source.sql`이다.

```sql
-- BY-1322: 기본 모드(TIMER) 세션 출처 (docs/adr/0049-timer-study-session-source.md)
-- active_study_session.source: draft의 출처. 확정한 세션이 이 값을 이어받는다. 기존 draft는 모두 관리 모드다.
-- planner_record_edit.source: 기본 모드 기록을 플래너에서 고치면 TIMER가 들어온다.
--   V54가 이름 없이 만든 열 CHECK의 PostgreSQL 자동 이름은 planner_record_edit_source_check다(V56·V62·V70과 같은 방식).
-- 락 대기 상한: active_study_session에 열을 더하는 동안 체크포인트 UPSERT가 막히므로 5초 안에 못 잡으면 배포를 실패시킨다(V38과 같다).
SET LOCAL lock_timeout = '5s';
ALTER TABLE active_study_session ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MEASURED';
-- 새 CHECK를 NOT VALID로 먼저 걸고 VALIDATE로 기존 행을 따로 검사하는 순서다.
-- VALIDATE 자체는 ACCESS EXCLUSIVE 대신 SHARE UPDATE EXCLUSIVE만 잡아 검사 중에도 읽기·쓰기를 막지 않는다.
-- 다만 Flyway는 이 파일을 한 트랜잭션으로 돌리므로, 앞의 ADD CONSTRAINT가 잡은 ACCESS EXCLUSIVE가 커밋까지 남아 검사도 그 락 아래에서 돈다.
-- 그래서 이 파일 안에서는 DROP과 ADD를 한 문장으로 쓸 때와 락 크기가 같고, 검사 실패 시 옛 CHECK가 남은 채 전부 되돌아가는 점도 같다.
-- 락까지 줄이려면 VALIDATE를 다음 버전 마이그레이션으로 분리해 별도 트랜잭션에서 돌려야 한다.
-- planner_record_edit는 사용자가 고친 기록만 담아 작으므로, 이번에는 한 파일로 두고 최종 제약 이름만 V54와 맞춘다.
ALTER TABLE planner_record_edit
    ADD CONSTRAINT planner_record_edit_source_check_v84 CHECK (source IN ('MEASURED', 'MANUAL', 'TIMER')) NOT VALID;
ALTER TABLE planner_record_edit VALIDATE CONSTRAINT planner_record_edit_source_check_v84;
ALTER TABLE planner_record_edit DROP CONSTRAINT planner_record_edit_source_check;
ALTER TABLE planner_record_edit
    RENAME CONSTRAINT planner_record_edit_source_check_v84 TO planner_record_edit_source_check;
```

- 상수 기본값이 있는 `ADD COLUMN`은 PostgreSQL 11부터 테이블을 다시 쓰지 않는다.
- `active_study_session`은 동시 공부 세션 수만큼의 작은 표다.
- `study_session.source`(V29)에는 CHECK가 없어 바꿀 것이 없다.
- `active_study_session.source`에도 V29처럼 CHECK를 두지 않는다.
- 옛 제약 이름은 이름을 찾는 `DO` 블록 대신 자동 이름을 직접 쓴다.
- 이 저장소의 V56·V62·V70이 같은 방식으로 자동 이름 CHECK를 바꿨다.
- 이름이 다르면 Flyway가 실패해 Testcontainers 테스트에서 바로 드러난다.
- 새 CHECK는 `NOT VALID`로 걸고 `VALIDATE CONSTRAINT`로 검사한 뒤 옛 CHECK를 떼고 이름을 `planner_record_edit_source_check`로 바꾼다.
- 그래서 최종 이름은 V54와 같고, 다음에 바꿀 때 V54와 V84 중 어느 이름인지 찾지 않아도 된다.
- `VALIDATE CONSTRAINT`는 `SHARE UPDATE EXCLUSIVE`만 잡는다.
- 하지만 Flyway가 이 파일을 한 트랜잭션으로 돌려서, 앞의 `ADD CONSTRAINT`가 잡은 `ACCESS EXCLUSIVE`가 커밋까지 남는다.
- 이 파일 안에서는 검사도 그 락 아래에서 돌아, `DROP`·`ADD`를 한 문장으로 쓸 때와 락 크기가 같다.
- `planner_record_edit`는 사용자가 고친 기록만 담아 작으므로 한 파일로 둔다.
- 검사 중 쓰기 대기까지 없애려면 `VALIDATE`를 다음 버전 마이그레이션으로 분리하면 된다.

## 호환

- 구 앱은 `API-Version` 없음 또는 1로 Legacy 컨트롤러에 온다.
- Legacy 요청 record에는 `source`가 없고 변환이 null을 넘기므로 모두 `MEASURED`로 저장된다.
- 구 앱에는 자율 모드가 없다.
- 새 웹이 옛 서버에 `source`를 보내는 경우는 서버가 모르는 필드로 무시해 `MEASURED`로 저장된다.
- 이 경우의 화면 처리는 BY-1321 쪽에서 다룬다.
- 옛 서버가 이 배포 전에 받은 자율 모드 세션은 `MEASURED`로 남는다.
- 그 기록은 고치지 않는다.
- 서버 안에서는 마이그레이션과 코드가 한 배포다. 서버와 웹의 순서는 아래 "배포 순서와 롤백"을 따른다.
- ECS 롤링 배포 중 옛 태스크는 `source` 열을 모르는 INSERT를 쓰고, 열 기본값이 `MEASURED`라 실패하지 않는다.
- 롤링 중 옛 태스크가 TIMER draft의 체크포인트를 받으면 그 draft의 `source`는 바뀌지 않는다.
- 옛 UPSERT의 `SET`에 `source`가 없기 때문이고, 다음 체크포인트가 새 태스크로 가면 다시 맞춰진다.

## 배포 순서와 롤백

### 배포 순서

1. 서버를 먼저 배포하고, ECS 롤링이 끝나 옛 태스크가 모두 내려갔는지 확인한다.
2. 그다음 `source: TIMER`를 보내는 웹을 배포한다.

이 순서면 옛 서버 태스크가 떠 있는 동안에는 TIMER 행이 생기지 않는다.
옛 태스크는 `TIMER`를 모르는 enum과 `source` 열이 없는 엔티티를 쓰므로, TIMER 행을 만나면 읽기에서 실패하거나 관리 모드로 확정한다.

### 롤백

웹을 먼저 되돌려 새 TIMER 행이 생기지 않게 한다.
서버를 TIMER가 없는 버전으로 되돌리기 전에는 이미 쌓인 TIMER 행을 먼저 처리해야 한다.
옛 서버는 세 표의 TIMER 행을 이렇게 다룬다.

| 표 | 옛 서버의 동작 |
|---|---|
| `study_session` | `StudySessionSource`에 `TIMER`가 없어 그 행을 읽는 조회가 500이 된다. |
| `planner_record_edit` | 같은 enum으로 읽어 플래너 조회가 실패한다. |
| `active_study_session` | `source` 열을 모르므로 TIMER draft를 관리 모드 세션으로 자동 확정한다. 랭킹과 포인트에 들어간다. |

`study_session`의 TIMER 행을 처리하는 방법은 세 가지다.

| 방법 | SQL | 얻는 것 | 잃는 것 |
|---|---|---|---|
| 관리 모드로 바꾸기 | `UPDATE study_session SET source = 'MEASURED' WHERE source = 'TIMER';` | 화면 표시가 지금과 가장 비슷하다. | 랭킹은 조회 때 집계하므로(ADR-0028) 지난 판까지 순위가 바뀐다. 연속 공부·마스코트·시작 미션에도 들어간다. 경쟁에서 빼기로 한 결정과 어긋난다. |
| 수동 기록으로 바꾸기 | `UPDATE study_session SET source = 'MANUAL' WHERE source = 'TIMER';` | 개인 합계에는 남고 랭킹·보상에서는 빠진다. TIMER의 정책과 같다. | 기록 배지가 직접 입력으로 바뀌고, 수동 기록 API로 고치거나 지울 수 있게 된다. 플래너 키가 `c:`에서 `m:`로 바뀐다. |
| 삭제하기 | 세션과 이벤트·구간 행을 함께 삭제한다. | 옛 서버가 만날 행이 없다. | 사용자의 공부 기록이 사라진다. 딸린 표가 많아 SQL도 가장 길다. |

수동 기록으로 바꾸는 방법을 권한다.
자율 모드를 경쟁·보상에서 빼고 개인 기록에는 남긴다는 결정을 옛 서버에서도 지킬 수 있는 값이 `MANUAL`뿐이다.
배지가 바뀌는 비용은 되돌릴 수 있다.
바꾸기 전에 대상 id를 남겨 두면, 다시 배포할 때 TIMER로 돌려놓을 수 있다.

```sql
-- 1. 웹을 되돌린 뒤, 남은 TIMER draft가 비기를 기다린다.
--    TIMER draft는 마지막 수신 뒤 2시간(TIMER_FINALIZE_GRACE)이 지나야 새 서버가 TIMER 세션으로 확정한다.
--    웹을 되돌리고 2시간이 지나기 전에는 아래 DELETE를 실행하지 않는다. 앱 밖에 있는 사용자의 진행 중 기록이 삭제된다.
SELECT count(*) FROM active_study_session WHERE source = 'TIMER';
-- 2시간을 기다린 뒤에도 남은 draft는 오래 열린 탭이 보고를 계속 보내는 경우다.
-- 옛 서버가 관리 모드로 확정하지 않게 삭제한다. 그 세션의 진행 중 기록은 잃는다.
DELETE FROM active_study_session WHERE source = 'TIMER';

-- 2. 되돌릴 때 쓸 백업을 남긴다.
CREATE TABLE by1322_rollback_session AS SELECT id FROM study_session WHERE source = 'TIMER';
CREATE TABLE by1322_rollback_edit AS SELECT * FROM planner_record_edit WHERE source = 'TIMER';

-- 3. 세션은 수동 기록으로 바꾸고, TIMER 수정본은 백업 뒤 뺀다.
UPDATE study_session SET source = 'MANUAL' WHERE source = 'TIMER';
DELETE FROM planner_record_edit WHERE source = 'TIMER';
```

- `planner_record_edit`의 TIMER 행은 바꾸지 않고 백업 뒤 뺀다.
- `MANUAL` 수정본은 원본 합계를 다루는 규칙(`preserve_original_totals`)이 달라, 출처만 바꾸면 기록이 다르게 보인다.
- 빼면 그 기록은 고치기 전 원본으로 보인다.
- 다시 배포할 때는 `study_session`을 백업 id로 `TIMER`로 돌리고, 백업한 수정본을 다시 넣은 뒤 백업 표를 삭제한다.

Flyway 기록은 이렇게 다룬다.

- 옛 서버에는 V84 파일이 없고 `flyway_schema_history`에는 V84 행이 남는다.
- Flyway 기본값(`ignoreMigrationPatterns = *:future`)은 적용됐지만 파일이 없는 미래 버전을 오류로 보지 않으므로 옛 서버는 문제없이 기동한다.
- V84의 스키마도 남겨 둔다.
- `active_study_session.source`는 기본값이 `MEASURED`라 옛 INSERT가 실패하지 않고, 넓힌 CHECK는 옛 값도 받는다.
- V84 행을 삭제하거나 파일을 고치지 않는다. 다시 배포하면 Flyway가 V84를 적용된 것으로 보고 건너뛴다.
- 열이나 CHECK를 손으로 되돌렸다면 V84 행도 함께 삭제해야 다음 배포에서 다시 적용된다.

## 자동 확정 뒤 최종 제출의 출처가 다를 때

관리 모드 draft가 자동 확정되면 그때 포인트가 지급된다.
그 뒤 같은 세션의 최종 제출이 TIMER로 오면 자동 확정본은 대체되지만, 지급한 포인트는 회수하지 않는다.

이 경우를 막는 코드는 두지 않는다.
앱은 세션을 시작할 때 모드를 정하고 세션 중에는 바꾸지 않으므로, 체크포인트와 최종 제출은 늘 같은 출처를 싣는다.
출처가 바뀌는 것은 요청을 꾸몄을 때뿐이다.
그런 사용자는 처음부터 `MEASURED`로 제출할 수 있으므로, 이 틈으로 더 얻는 것이 없다.

## 테스트 계획

공통 도구는 이렇다.

- `RankingIntegrationTestBase`에 `timerSession(userId, start, minutes, focusSec)`을 더한다.
- `timerSession`은 `session`과 같은 실제 제출 경로에 `source = TIMER`를 넣는다.
- TIMER draft는 기존 draft 도우미로 만든 뒤 `UPDATE active_study_session SET source = 'TIMER' WHERE user_id = ?`로 바꾼다.
- `group/support/StudyFixture`에 `timerSession(userId, start, focusSec)`와 `timerStudying(userId, reportedFocusSec)`를 더한다.
- `MemberStudySourceTest`는 이미 `sessionSource` 인자로 확정 행을 만들므로 `'TIMER'`를 넘긴다.

### 요청·응답

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `StudySessionApiTest` | `source_없이_제출하면_MEASURED로_저장된다` | 기본값 |
| `StudySessionApiTest` | `source가_TIMER면_TIMER로_저장되고_응답에_실린다` | 제출 경로의 `markTimer` |
| `StudySessionApiTest` | `source가_MANUAL이면_400_VALIDATION_FAILED다` | `@AssertTrue` |
| `LegacyStudySessionApiTest` | `구_앱_제출은_source를_보내도_MEASURED로_저장된다` | 구 앱 동작 유지 |
| `ActiveSessionSnapshotApiTest` | `TIMER_스냅샷은_draft에_TIMER로_저장되고_재접속_조회에_실린다` | 버퍼 경로 UPSERT와 `GET /active` |
| `ActiveSessionSnapshotApiTest` | `durable_보고도_source를_저장한다` | durable 경로 |
| `ActiveSessionSnapshotApiTest` | `나중_스냅샷의_source가_앞의_값을_덮어쓴다` | 마지막 값이 이김 |
| `ActiveSessionSnapshotApiTest` | `MANUAL_스냅샷은_400이다` | 체크포인트의 `@AssertTrue` |

### 확정·멱등·복구

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `ActiveSessionFinalizeTest` | `TIMER_draft는_TIMER_세션으로_자동_확정된다` | 확정이 draft 출처 유지 |
| `AutoFinalizedReplaceApiTest` | `TIMER_자동_확정본은_늦은_최종_제출로_대체된다` | 대체 조회가 TIMER를 찾음 |
| `StudySessionIdempotencyApiTest` | `TIMER_세션을_같은_시각으로_재제출하면_409가_아니라_기존_세션을_돌려준다` | 재전송 409 수정 |
| `SessionRecoveryServiceTest` | `TIMER_draft가_있으면_확정하고_요약을_반환한다` | 복구 요약 500 수정 |
| `SessionRecoveryServiceTest` | `draft가_없고_최근_TIMER_자동확정본이_미확인이면_요약을_반환한다` | `SourceNot` 조회 |

### 랭킹

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `RankingExcludesManualApiTest` | `기본_모드_기록만_있는_사용자는_어느_판에도_오르지_않는다` | 확정 집계, 기존 판 목록과 같은 매개변수 |
| `RankingExcludesManualApiTest` | `진행_중인_기본_모드_draft는_랭킹에_들어가지_않는다` | `RankingSource.livePieces` 필터 |
| `RankingCloserTest` | `마감_확정은_기본_모드_draft를_세지_않는다` | `RankingSource.settledPieces` 필터 |
| `ActiveSessionLivePiecesTest` | `조각은_draft의_source를_싣는다` | `toLivePieces`의 출처 |

`RankingExcludesManualApiTest`의 클래스 Javadoc은 "수동 기록과 자율 모드 기록은"으로 고친다.

### 그룹

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `MemberStudySourceTest` | `기본_모드_확정_세션도_오늘과_이번_주와_마지막_공부에_들어간다` | `focusTotals`·`lastStudiedEndedAt` |
| `MemberStudySourceTest` | `기본_모드_draft도_STUDYING이고_순공에_들어간다` | 진행 중 포함 |
| `MemberStudySourceTest` | `weekFocus는_관리_모드_순공을_따로_센다` | 확정·진행 중 각각의 `measuredSec` |
| `GroupWeeklyFocusTest` | 기존 케이스의 목 반환값을 `WeekSec`로 바꾸고 `정렬용_합계는_관리_모드_순공만_더한다`를 더한다 | `measuredTotalSec` |
| `PublicGroupsApiTest` | `기본_모드_순공도_합계와_평균에_들어간다` | 카드 표시값 |
| `PublicGroupsApiTest` | `정렬은_관리_모드_순공_합계로_한다` | 자율 모드로 합계가 커도 앞에 오지 않음 |

### 개인 기록

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `StudySessionApiTest` | `일간_조회는_기본_모드를_합계와_달력에_넣고_집중률과_최대_집중은_관리_모드만_본다` | `list` 필터 |
| `ManualStudySessionStatsApiTest` | `과목_누적과_기간_통계에는_기본_모드_기록이_들어간다` | 개인 합계 포함 |
| `ManualStudySessionStatsApiTest` | `기본_모드_기록만_있는_날은_연속_공부에_세지_않는다` | 연속 공부 제외 |

### 보상

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `StudyPointApiTest` | `기본_모드_기록은_포인트로_인정하지_않는다` | 제출 경로로 보낸 TIMER가 포인트 없음 |
| `HomeMissionApiTest` | `기본_모드_순공은_미션에_합산하지_않는다` | 시작 미션 |
| `MascotApiTest` | `기본_모드_순공으로는_마스코트가_열리지_않는다` | 마스코트 |

상점의 `maxStreakDays`는 연속 공부와 같은 조건이라 새 케이스를 두지 않는다.

### 플래너와 마이그레이션

| 테스트 클래스 | 새 케이스 | 증명하는 것 |
|---|---|---|
| `PlannerRecordEditContractApiTest` | `subjectOnlySavePreservesPreciseTimesAndDailySubjectPeriodAndRankingTotals`에 `TIMER,true,5000,3210`과 `TIMER,false,5000,3210`을 더한다 | `c:` 조회가 TIMER를 찾고 V84 CHECK가 TIMER를 받으며 랭킹 합계는 비어 있음 |
| `PlannerRecordEditContractApiTest` | `editedRangeRemainsCompleteAndChangingOneEdgePreservesTheOtherPreciseEdge`에 `TIMER`를 더한다 | 시간 수정 경로 |
| `PlannerRecordUserDataHandlerTest` | `timerRecordWhoseOriginalIsDroppedBySessionMergeIsNotKeptAsOrphan` | 원본 없는 수정본 숨김 |
| `PersonalRecordStatsApiTest` | `dayCountsTimerInTotalsButNotInFocusRateOrLongest` | 플래너 일간의 기존 `measured()` 기준 |

`PlannerRecordEditContractApiTest`는 키 접두사를 `source.equals("MANUAL") ? "m:" : "c:"`로, 랭킹 단언을 `MEASURED`일 때만 값이 있는 것으로 고친다.

V84의 열 추가는 위 체크포인트·확정 테스트가, CHECK 교체는 플래너 TIMER 케이스가 실제 PostgreSQL에서 확인한다.
마지막으로 `./gradlew check`를 통과시킨다.

## 남은 질문

- 누적 공부일(`GET /api/stats/study-days`)과 공부 리듬(`GET /api/stats/rhythm`, 플래너 리듬)은 2026-11-19에 지금 동작을 유지하기로 했다.
- 둘 다 `MEASURED`만 세고 자율 모드(TIMER)는 빠진다.
- 사용자가 모드별 시간을 어떻게 보는지는 뒤의 프론트엔드 티켓에서 정한다.
- 그룹 공부 시작 알림은 자율 모드 draft에도 나간다. 지금 동작 그대로다.
