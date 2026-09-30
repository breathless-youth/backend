# BY-734 기록 탭 일간 조회 확장 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/stats?date=` 한 번으로 날짜 상세·타임테이블·세션 바텀시트를 그릴 수 있게, 세션마다 이벤트·과목 구간·완료 할 일(이름 포함)을 싣고 응답에 과목 이름·색을 붙인다. 지운 과목·어제 완료한 할 일의 이름도 실린다.

**Architecture:** 세션 도메인이 소유한 `SubjectLookupProvider` 인터페이스를 과목 서비스가 구현한다(의존 방향 subject → studysession 유지, ADR-0021 §6). `StudySessionService`가 응답을 만들 때 세션들이 참조한 과목·할 일 id를 모아 한 번 조회하고 DTO에 붙인다. 구 앱(v1) 컨트롤러는 v2 컨트롤러 메서드에 위임하므로 같은 응답을 받는다 — 필드 추가만이라 영향 없다.

**Tech Stack:** BY-733 계획과 같다.

**Spec:** `docs/superpowers/specs/2026-09-22-by556-record-tab-v2-design.md` §5. §5.2의 "컨트롤러 조립"은 이 계획에서 "서비스 조립 + 제공자 인터페이스"로 바꿨다(구 앱 위임 구조 때문에 컨트롤러 조립은 두 곳이 어긋날 수 있다).

## Global Constraints

- 브랜치 `feature/BY-734-record-day-detail` — `feature/BY-733-subject-segments`(b0b0be0) 위에 스택. PR base도 BY-733 브랜치.
- 구 앱이 읽는 필드는 유지하고 **추가만**. `completedTaskIds`는 구 앱 출시 뒤 생긴 필드라 `completedTasks[]`로 교체.
- 이름 조회는 `deleted_at`을 무시한다(`JpaRepository.findAllById`). 소유는 세션이 이미 그 유저 것이라 다시 보지 않는다.
- 배열은 id 오름차순으로 안정화한다.
- 커밋은 `./gradlew check` 통과 뒤. Codex 리뷰 P1이면 FAIL. 퀴즈는 사용자 지시로 생략.

---

### Task 1: 이름 조회 제공자와 응답 확장

**Files:**
- Create: `studysession/dto/SubjectRef.java`, `studysession/dto/CompletedTaskResponse.java`, `studysession/dto/SubjectLookup.java`, `studysession/service/SubjectLookupProvider.java`, `src/test/java/project/study/studysession/StudyRecordLookupApiTest.java`
- Modify: `dto/StudySessionResponse.java`, `dto/StudySessionSummaryResponse.java`, `dto/StudySessionListResponse.java`, `service/StudySessionService.java`, `subject/service/StudySubjectService.java`, `controller/StudySessionController.java`(Swagger), `controller/StudySessionStatsController.java`(Swagger), 서비스 생성자를 부르는 테스트 9개, `StudySessionCompletedTaskApiTest`(경로 `completedTasks[i].id`)

**Interfaces:**
- `SubjectRef(Long id, String name, int colorIndex, boolean deleted)`
- `CompletedTaskResponse(Long id, String name, Long subjectId, boolean deleted)`
- `SubjectLookup(Map<Long, SubjectRef> subjects, Map<Long, CompletedTaskResponse> tasks)` + `EMPTY`, `subjectsFor(ids)`, `tasksFor(ids)`, `subjectsReferencedBy(StudySession)`, `allSubjects()`
- `SubjectLookupProvider.lookup(Collection<Long> subjectIds, Collection<Long> taskIds) → SubjectLookup`
- `StudySessionService(StudySessionRepository, ActiveStudySessionRepository, Clock, SubjectLookupProvider)`
- `StudySessionResponse.from(session, focusRate, lookup)`, `StudySessionSummaryResponse.from(session, focusRate, eventCounts, lookup)`

- [ ] **Step 1: 새 레코드·인터페이스** — 본문은 아래 구현과 같다.
- [ ] **Step 2: 응답 DTO 확장** — `StudySessionResponse`: `completedTaskIds` → `completedTasks`, `subjects` 추가. `StudySessionSummaryResponse`: `events`·`subjectSegments`·`completedTasks` 추가. `StudySessionListResponse`: `subjects` 추가.
- [ ] **Step 3: 서비스 조립** — `StudySessionService`에 `SubjectLookupProvider` 주입, `lookupFor(sessions)`로 한 번 조회, `toResponses`·`list`·`findById`가 붙인다. `StudySubjectService implements SubjectLookupProvider`.
- [ ] **Step 4: 테스트** — 생성자 9곳에 `(subjectIds, taskIds) -> SubjectLookup.EMPTY` 추가, `StudySessionCompletedTaskApiTest` 경로 갱신, `StudyRecordLookupApiTest` 신규: 일간 목록에 events·subjectSegments·completedTasks·subjects, 지운 과목·할 일 deleted=true, 어제 완료 할 일이 `/api/subjects`에는 없지만 이름이 실림, 상세·제출 응답 동일, 과목·할 일 없는 세션은 빈 배열.
- [ ] **Step 5: Swagger** — 제출 201·상세 200·일간 목록 설명에 `completedTasks`·`subjects`·세션별 `events`/`subjectSegments`.
- [ ] **Step 6: `./gradlew check`** → Codex 리뷰 → 커밋 `feat: 기록 탭 일간 조회에 세션별 구간·이벤트·완료 할 일과 과목 이름·색을 싣는다 (BY-734)`.

### Task 2: 문서·PR

- [ ] ADR-0023 결과 절에 BY-734 조립 방식(제공자 인터페이스) 추가, spec §5.2 갱신, 이 계획 파일 커밋 `docs:`.
- [ ] push → `gh pr create --base feature/BY-733-subject-segments`, 머지하지 않음. BY-734 검토 중.
