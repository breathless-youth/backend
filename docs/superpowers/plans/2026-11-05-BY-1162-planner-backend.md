# BY-1162 Planner Backend Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for the planner, and superpowers:dispatching-parallel-agents for the independent record/goal subsystems. The user explicitly waived the quiz gate for this task only; commit after verification.

**Goal:** 승인된 날짜별 플래너의 서버 기능 7개와 데이터 이전·Swagger 계약을 제공한다.

**Architecture:** 기존 study_task id/완료 시각을 유지하며 날짜와 반복 버전/예외를 더한다. 측정 원본은 보존하고 플래너용 기록 수정본을 합성한다. 모든 새 경로는 기존 토큰 인증/API-Version 1이다.

**Tech Stack:** Java 25, Spring Boot 4.1, JPA/JdbcClient, PostgreSQL, Flyway, MockMvcTester, Testcontainers. 의존성 추가 없음.

**Spec:** `docs/superpowers/specs/2026-11-05-BY-1162-planner-backend-design.md`

## Global Constraints

- 사용자 현지 05:00~다음 날 05:00, DST 고려.
- 전체 과목 20, 새 과목 이름 12자, 새 할 일 이름 25자, 날짜·과목별 회차 포함 30개.
- 과거 완료는 계획 날짜 귀속. 이동·가져오기 실패 시 원본 보존.
- 기존 API와 측정 원본·랭킹·완료 이력 보존. SecurityConfig/의존성 변경 없음.
- 기능 전체 준비 후 공개. 커밋 전 check·독립 리뷰. 이번 작업의 사용자 퀴즈는 명시적 사용자 지시로 생략한다.

## Review Focus

- 종료일 없는 반복 규칙끼리 먼 미래에 겹칠 때에도 날짜별 한도를 지킨다.
- 반복 규칙 변경이 개별 이동·이름 변경 예외와 과거 완료를 지우지 않는다.
- 가져오기 실패와 응답 유실 후 재시도가 원본 또는 미처리 상태를 잃지 않는다.
- DST/05시/시간대 변경 시 일별 집계와 완료 귀속이 일치한다.
- 기록 편집·삭제·계정 병합·탈퇴 뒤 랭킹 원본과 개인정보 처리의 일관성을 지킨다.

### Task 1: BY-1169 스키마·날짜·이전

**Files:** V53 planner migration, `planner/service/PlannerCalendar.java`, StudyTask 날짜/반복 필드, migration API tests.
**Interfaces:** PlannerCalendar.today(Clock, ZoneId), start(LocalDate, ZoneId). 기존 study_task.id/done_at 유지.

- [x] 현지 04:59/05:00 및 DST 하루 경계 단위 테스트를 RED로 실행한다.
- [x] 날짜 계산과 V53(날짜별 과목·반복·study_task 확장/이전)을 구현한다.
- [x] PostgreSQL에서 미완료·완료·삭제 데이터의 날짜·id·완료 시각 및 재실행 중복 방지를 검증한다.

### Task 2: BY-1163 날짜별 계획

**Files:** planner DTO/controller/service/repository, StudySubject DTO 한도, planner API tests.
**Interfaces:** `PlannerTaskService.tasks(userId, from, to)`는 일반/가상 회차 목록. key는 t:id 또는 r:seriesId:date. 요청 timeZone은 UserTimeZones로 해석한다.

- [x] 날짜별 선택·순서·CRUD·완료·소유/과거·미래 검증과 한도 초과 이동 원복 API 테스트를 RED로 실행한다.
- [x] 사용자 락과 트랜잭션 안에서 날짜별 변경/조회/합집합을 구현한다.
- [x] 세션용 회차 실체화와 과목/할 일 이름 한도를 기존 경로에도 적용하고 회귀 테스트한다.

### Task 3: BY-1164 반복

**Files:** planner recurrence rules/resolver/capacity, recurrence tests.
**Interfaces:** Task 2의 key와 task 조회를 사용. rule 버전의 유효 기간과 materialized occurrence 우선.

- [x] 요일·종료일·지난 회차·개별 미래 예외·안 함·요일 밖 시작·먼 미래 한도 테스트를 RED로 실행한다.
- [x] 규칙 버전과 회차 실체화, 모든 미래의 한도 검사, 날짜 이동 중복 검사를 구현한다.
- [x] 조회/변경 테스트를 실행한다.

### Task 4: BY-1165 목표 (독립 작업)

**Files:** V55 goal migration, planner/goal DTO/repository/service/controller/tests, goal account handler.
**Interfaces:** `PlannerGoalService.get(Long userId, LocalDate date)` returns `PlannerGoalResponse(Integer goalSec, Integer individualGoalSec, Integer defaultGoalSec)`; query uses stored user zone. Goal endpoints /api/planner/goals.

- [x] 기본값 상속·개별 0·해제·경계 검증·소유 테스트를 RED로 실행한다.
- [x] 기본 목표/날짜별 목표를 별도로 구현하고 계정 병합·탈퇴를 처리한다.
- [x] 서비스 단위 및 API 통합 테스트를 실행한다.

### Task 5: BY-1166 미완료 가져오기

**Files:** planner carryover service/controller/tests.
**Interfaces:** Task 2/3의 stable key와 날짜별 한도 검사. 요청 offeredKeys/selectedKeys.

- [x] 반복 요일 필터·계열별 최근 1개·무시·실패 원복·재시도 테스트를 RED로 실행한다.
- [x] 가져오기/미선택 무시를 전체 트랜잭션으로 구현한다.
- [x] API 테스트를 실행한다.

### Task 6: BY-1167 기록 편집 (독립 작업)

**Files:** V54 record migrations, `planner/record/` record views/services/repository/controller/tests/account handler. 기존 측정·랭킹 코드는 수정하지 않는다.
**Interfaces:** `PlannerRecordService.records(Long userId, Instant from, Instant to)` returns `List<PlannerRecordView>` clipped to requested window. View fields: `String key, StudySessionSource source, Instant startedAt, Instant endedAt, long studySec, long focusSec, List<PlannerRecordSegment> subjectSegments`; segment fields: `Long subjectId, Instant startedAt, Instant endedAt, long studySec, long focusSec`. New batch endpoint `/api/planner/records/batch`.

- [x] 측정 수정/삭제의 개인 집계와 랭킹 불변, 이벤트 절삭, 부분 실패, 요청 재시도, 수동 묶음 API 테스트를 RED로 실행한다.
- [x] 원본 보존 수정본과 operationId/해시 기반 멱등 처리, 항목별 트랜잭션·결과를 구현한다.
- [x] 기록 창 조회와 계정 병합·탈퇴를 검증한다.

### Task 7: BY-1168 통합 조회·문서·전체 검증

**Files:** planner day/range query/controller, ADR-0044, planner API contract, account handler, all tests.
**Interfaces:** Tasks 2/3의 할 일, Task 4 목표, Task 6 기록을 합쳐 날짜별 응답/요약/달력 상태를 제공한다.

- [x] 공부 기록에만 있는 삭제 과목·미래 계획·05시/DST·주간 합계 테스트를 RED로 실행한다.
- [x] 날짜·기간 상세/요약 API, 기록 기반 과목 합집합, 계정 데이터 처리를 완성한다.
- [x] Swagger 요청/응답/오류 예시, 기존 계약과의 차이, 이전 검증 SQL, ADR를 작성한다.
- [x] `./gradlew spotlessApply` 뒤 `./gradlew check`를 실행하고 독립 리뷰의 P1/P2를 수정·검증한다.
- [x] 퀴즈 5개를 제시했고 사용자가 이번 작업에 한해 생략을 지시했다. 전체 검증 후 로컬 커밋하며 PR·배포는 진행하지 않는다.

## 최종 검증

- `./gradlew spotlessApply check --console plain`: BUILD SUCCESSFUL, 전체 테스트 2,198개, 실패/오류/스킵 0.
- 새 플래너 테스트에는 V52→V55 이전, 반복 예외/한도, 계정 병합, 과거 완료, DST/05시, Swagger 예시, 기록 재시도가 포함된다.
- 독립 리뷰 P1/P2는 재현 테스트 RED→GREEN으로 수정했다. 사용자가 이번 작업의 퀴즈를 생략하도록 지시했다. 최종 검증 후 로컬 커밋하며 PR·배포·FE/앱 연동은 별도 작업으로 남긴다.
