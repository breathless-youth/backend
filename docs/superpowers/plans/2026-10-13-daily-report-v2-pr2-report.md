# 일일 리포트 v2 PR ② 리포트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR ①이 적재한 Amplitude 데이터와 서버 데이터로 요약 1개 + 스레드 6개의 새 일일 리포트를 Slack 봇으로 보내고, 옛 리포트를 지운다.

**Architecture:** 도메인 서비스가 "기간 안 사실"(`metrics.report.fact` 레코드)을 돌려주고, `ReportDataLoader`가 유저별
`UserTimeline`으로 묶는다. 분석기 6개는 `ReportData`만 받는 순수 함수라 단위 테스트로 고정하고, 렌더러가 섹션 결과를
부록 A 형식 문자열로 바꾼다. `DailyReportService`가 섹션별 실패를 격리해 조립하고 `SlackBotNotifier`(`chat.postMessage`)로
본 메시지 ts에 스레드를 단다. 실행은 PR ①의 `MetricsJobRunner` 단일 스레드에서 10:00에 돈다.

**Tech Stack:** Spring Boot 4.1, Java 25, JdbcClient, Spring Data, RestClient(JDK HttpClient), Jackson 3(`tools.jackson`),
Spring `@Retryable`(resilience), Testcontainers PostgreSQL, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-13-daily-report-v2-design.md` (§3 공통 정의, §4 섹션 정의, §5 규칙 카탈로그, §6 노출 조건,
§8 코드 구조, §9 Slack, §10 실패 처리, §11 설정, §12 테스트, 부록 A 메시지 예시). 결정 기록 ADR-0030.

## Global Constraints

- 기준일 A = 발송일 KST 어제. 가입일 D0 = `users.created_at`의 KST 날짜, Dn = D0 + n일. 첫 주 = D0~D6, 둘째 주 = D7~D13.
- 공부한 날 = 그 `stat_date`에 `focus_sec >= 600`(`StudySessionThresholds.MIN_STREAK_FOCUS_SEC`)인 세션이 하나 이상. 자정 분할
  조각은 병합하지 않는다(ADR-0012 결정 3). 하루 순공 = 그 `stat_date` 세션들의 `focus_sec` 합.
- 원본 사실은 `[A−61, A]`, 가입자는 `[A−68, A]`. 세션만 `A+1`까지 읽는다(어제 23시 가입자의 "3시간 안 공부").
- metrics는 다른 도메인 테이블을 읽지 않는다. user·studysession·room·subject·dday 서비스가 `metrics.report.fact` 레코드를 돌려준다.
- 비율 표기 `57% (8/14)`, 분모 0이면 `—`. 퍼센트는 정수 반올림(half-up).
- 노출 조건: 한 사람·안 한 사람 각각 5명 이상, 한 사람 비율 90% 이하, phi ≥ 0.2. 강도 0.4 이상 강함 / 0.3 보통 / 0.2 약함.
  순위 = phi 내림차순 → 한 사람 수 내림차순 → 카탈로그 순서.
- 제외 유저 `metrics.report.excluded-user-ids`(기본 빈 목록)는 모든 섹션에서 뺀다. 탈퇴 유저는 코호트에 남긴다.
- Amplitude 판정 기간에 미수집일이 하나라도 있으면 그 Amplitude 규칙은 "Amplitude 미수집"으로 빠지고 0으로 세지 않는다.
- Checkstyle: 파일 400줄, 메서드 60줄, 순환 복잡도 10, 파라미터 7개(테스트 포함).
- `@Profile` 금지, 기능은 `@ConditionalOnProperty(app.report.enabled)`. `TaskScheduler`·`Executor` 타입 빈을 새로 만들지 않는다.
- 봇 토큰은 로그·예외 메시지·Sentry 어디에도 남기지 않는다. `SlackNotificationException`은 원인을 붙이지 않는다.
- 생성자 주입, DTO는 record, 엔티티 `@Data` 금지. 새 의존성 없음.
- 커밋: Conventional Commits + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 퀴즈 게이트(CLAUDE.md 규칙 7)를 통과하거나
  사용자가 생략을 지시한 뒤 태스크별 커밋으로 나눈다. 각 커밋은 `./gradlew check` 통과 상태여야 한다.

## Review Focus

1. **빈 데이터** — 최근 7일 가입 0명, 코호트 0명, 첫 주 3일+ 0명, 어제 공부 0명: 0으로 나누기·`NaN`·빈 목록 예외 없이 각 줄이
   "없음"/`—`로 나와야 한다. → Task 5·6·7 분석기 테스트, Task 8 렌더러 테스트.
2. **Amplitude 미수집**(적재 꺼짐·첫 배포): Amplitude 규칙은 "Amplitude 미수집"으로 집계, 카드에 "(Amplitude 일부 미수집)",
   "앱만 열고 공부 안 함"은 "Amplitude 미수집"으로 표시. → Task 6·7 테스트.
3. **닉네임·분야·가입 시각이 비어 있는 유저**: 가입 시각 없는 행은 날짜를 못 매기니 로더가 빼고, 닉네임 없음은 "닉네임 없음",
   분야 없음은 "분야 미입력". → Task 1 유저 조회 테스트, Task 6 카드 테스트.
4. **Slack 부분 실패**: 본 메시지가 실패하면 스레드를 달지 않고, 스레드 하나가 실패해도 나머지는 보낸다. 429는 Retry-After 뒤 재시도.
   → Task 9 테스트.
5. **자정 경계**: 23시 가입자가 00:30에 시작한 세션은 "3시간 안 공부"에 들고, 자정 분할 조각은 각각 10분을 넘어야 공부한 날이다.
   → Task 2·5 테스트.

## Rulings (스펙·부록과 다르게 정한 것)

- 요약 4번째 줄은 부록의 "첫 주에 소셜 방 3번 이상" 대신 규칙 이름 그대로("… 참여")를 쓴다 — 규칙 이름은 코드 파라미터에서
  만든다(스펙 §5). Task 11에서 부록을 고친다.
- 탐색 스레드의 규칙 상세 줄은 1위만이 아니라 모두 "둘째 주에도 공부 … vs 안 한 사람 …" 긴 형식으로 쓴다. Task 11에서 부록을 고친다.
- 카드의 Amplitude 행동(간단 모드·백색소음 등)은 횟수 없이 이름만, 서버 행동과 앱 실행 일수만 숫자를 붙인다(부록 A 예시 형식).
- "세션 평균 순공·세션당 일시정지·시작 시간대" 같은 습관 값은 순공 1분 이상 세션(`MIN_LIST_FOCUS_SEC`, 앱 기록 목록과 같은
  잣대)만 센다. 하루 순공·공부한 날은 스펙대로 모든 세션이다.
- 연속 표시: 1위면 "k일째 1위"(그 전 날들도 1위였던 연속 일수), 2~3위면 "k일째 상위권"(Top 3 연속 일수), k = 1이면 "오늘 처음 1위"/
  "오늘 처음 상위권". 비교는 A−1·A−2까지(최대 3일째).

---

## 파일 구조

```
src/main/java/project/study/
  metrics/report/fact/      UserFact · SessionFact · UserEventFact · SubjectFact   (도메인 → metrics 사실 레코드)
  metrics/amplitude/        AmplitudeDailyCount · AmplitudeAttribute · AmplitudeQueryService
  metrics/amplitude/repository/AmplitudeQueryRepository
  metrics/report/           Kst · Rate · AmplitudeKeys · UserTimeline · ReportData · ReportDataLoader · Cohorts
                            FirstWeekHabits · Categories · Profiles · OsLabels · Attributions
                            SectionResult · DailyReport · DailyReportService · DailyReportJob
  metrics/report/rule/      RuleGroup · RuleSource · SegmentRule · CohortStats · TwoByTwo · Exposure · Strength
                            StudyRules · HabitRules · ActionRules · RuleCatalog
  metrics/report/analyzer/  SignupAnalyzer·SignupSection · RetentionAnalyzer·RetentionSection
                            SegmentAnalyzer·SegmentSection·SegmentProfiles · SegmentRoster·RosterSection·RosterCards
                            RuleExplorer·ExplorerSection · ActiveUserClassifier·ActiveUsersSection
  metrics/report/render/    Fmt · SummaryRenderer · SignupRenderer · RetentionRenderer · SegmentRenderer
                            RosterRenderer · ExplorerRenderer · ActiveUsersRenderer · ReportRenderer · RenderedReport
  metrics/slack/            SlackNotifier(교체) · SlackBotNotifier(신규) · SlackNotificationException(유지)
  metrics/job/              MetricsRunOnce(리포트 추가)
  studysession/repository/  SessionFactQueries(신규)        studysession/service/StudySessionMetricsService(교체)
  user/                     UserService·UserRepository(리포트 유저 조회, 옛 메서드 삭제)
  room/                     RoomMetricsQueries · RoomMetricsService
  subject/                  SubjectMetricsQueries · SubjectMetricsService
  dday/                     DdayRepository(조회 추가) · DdayMetricsService
삭제: metrics/dto/* · metrics/service/DailyReportService · metrics/scheduler/DailyReportScheduler ·
      metrics/slack/SlackWebhookNotifier · studysession/repository/StudySessionMetricsRepository·QualifyingSessionRow
```

테스트 픽스처: `src/test/java/project/study/metrics/report/TimelineBuilder.java`(유저 한 명의 사실을 날짜 단위로 쌓는 빌더),
`ReportDataFixture.java`(타임라인 목록 → `ReportData`).

---
### Task 1: 도메인 사실 조회

**Files:**
- Create: `src/main/java/project/study/metrics/report/fact/UserFact.java`, `SessionFact.java`, `UserEventFact.java`, `SubjectFact.java`
- Create: `src/main/java/project/study/studysession/repository/SessionFactQueries.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionMetricsService.java` (메서드 추가)
- Modify: `src/main/java/project/study/user/repository/UserRepository.java`, `src/main/java/project/study/user/service/UserService.java`
- Create: `src/main/java/project/study/room/repository/RoomMetricsQueries.java`, `src/main/java/project/study/room/service/RoomMetricsService.java`
- Create: `src/main/java/project/study/subject/repository/SubjectMetricsQueries.java`, `src/main/java/project/study/subject/service/SubjectMetricsService.java`
- Modify: `src/main/java/project/study/dday/repository/DdayRepository.java`; Create: `src/main/java/project/study/dday/service/DdayMetricsService.java`
- Create: `src/main/java/project/study/metrics/amplitude/AmplitudeDailyCount.java`, `AmplitudeAttribute.java`, `AmplitudeQueryService.java`,
  `src/main/java/project/study/metrics/amplitude/repository/AmplitudeQueryRepository.java`
- Test: `src/test/java/project/study/studysession/SessionFactQueryIntegrationTest.java`,
  `src/test/java/project/study/metrics/report/ReportFactQueryIntegrationTest.java`,
  `src/test/java/project/study/metrics/amplitude/AmplitudeQueryServiceIntegrationTest.java`

**Interfaces:**
- Consumes: 기존 테이블(users, study_session, status_event, room_participations, study_subject, study_task, user_dday, V28 세 테이블),
  `AmplitudeIngestRepository.findProcessedDates(LocalDate, LocalDate): Set<LocalDate>`
- Produces:
  - `record UserFact(long userId, String nickname, Instant createdAt, String category, String goal)`
  - `record SessionFact(long userId, LocalDate statDate, Instant startedAt, int focusSec, int pauseCount, boolean social)`
  - `record UserEventFact(long userId, Instant at)`, `record SubjectFact(long userId, Instant createdAt, Instant deletedAt)`
  - `StudySessionMetricsService.findSessionFacts(LocalDate from, LocalDate to): List<SessionFact>` (양끝 포함, 10분 미만 포함)
  - `UserService.findReportUsers(Instant signedUpFrom, Collection<Long> alsoUserIds): List<UserFact>` (가입 시각 없는 행 제외)
  - `RoomMetricsService.findJoins(Instant from, Instant to): List<UserEventFact>` (`joined_at ∈ [from, to)`)
  - `SubjectMetricsService.findSubjectsOf(Collection<Long> userIds): List<SubjectFact>`,
    `SubjectMetricsService.findTasksDone(Instant from, Instant to): List<UserEventFact>`
  - `DdayMetricsService.findDdaysOf(Collection<Long> userIds): List<UserEventFact>` (at = created_at)
  - `record AmplitudeDailyCount(long userId, LocalDate date, String key, int count)`,
    `record AmplitudeAttribute(String utmSource, boolean adClick, String osName)`
  - `AmplitudeQueryService.findCounts(LocalDate from, LocalDate to, Collection<String> keys): List<AmplitudeDailyCount>`,
    `findAttributes(Collection<Long> userIds): Map<Long, AmplitudeAttribute>`, `collectedDates(LocalDate from, LocalDate to): Set<LocalDate>`

- [ ] **Step 1: 세션 사실 조회 테스트를 쓴다**

`src/test/java/project/study/studysession/SessionFactQueryIntegrationTest.java` — 옛 `QualifyingSessionQueryIntegrationTest`의 소셜 판정
사례를 그대로 옮기고(Task 10에서 옛 테스트를 지운다), 10분 미만 포함·일시정지 수·기간 경계를 더한다.

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.metrics.report.fact.SessionFact;
import project.study.studysession.service.StudySessionMetricsService;

/** 리포트 v2 세션 사실 조회 — 10분 미만 포함, 일시정지 수, 소셜(방 참여 겹침) 판정 (BY-904). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SessionFactQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2020, 2, 10);

    @Autowired
    private StudySessionMetricsService studySessionMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private long insertSession(long userId, LocalDate statDate, Instant startedAt, Instant endedAt, int focusSec) {
        return jdbcTemplate.queryForObject(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                userId,
                java.sql.Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(endedAt),
                focusSec,
                focusSec);
    }

    private long insertSession(long userId, Instant startedAt, Instant endedAt) {
        return insertSession(userId, DAY, startedAt, endedAt, 600);
    }

    private void insertEvent(long sessionId, String status) {
        jdbcTemplate.update(
                "insert into status_event (session_id, status, started_at, ended_at) values (?, ?, ?, ?)",
                sessionId,
                status,
                Timestamp.from(at(1)),
                Timestamp.from(at(1).plusSeconds(60)));
    }

    // 닫힌 방으로 넣는다 — 열린 방 코드 부분 유니크 인덱스와 다른 테스트가 충돌하지 않게
    private long insertRoom(long createdBy) {
        return jdbcTemplate.queryForObject(
                "insert into rooms (invite_code, created_by, created_at, closed_at, close_reason) "
                        + "values ('0000', ?, now(), now(), 'LAST_LEFT') returning id",
                Long.class,
                createdBy);
    }

    private void insertParticipation(long roomId, long userId, Instant joinedAt, Instant leftAt) {
        jdbcTemplate.update(
                "insert into room_participations (room_id, user_id, reserved_at, stomp_confirmed, joined_at, left_at) "
                        + "values (?, ?, ?, true, ?, ?)",
                roomId,
                userId,
                Timestamp.from(joinedAt),
                Timestamp.from(joinedAt),
                leftAt == null ? null : Timestamp.from(leftAt));
    }

    private Instant at(int hour) {
        return DAY.atStartOfDay(KST).toInstant().plusSeconds(hour * 3600L);
    }

    private List<SessionFact> factsOf(long userId) {
        return studySessionMetricsService.findSessionFacts(DAY, DAY).stream()
                .filter(fact -> fact.userId() == userId)
                .toList();
    }

    private boolean socialOf(long userId) {
        return factsOf(userId).getFirst().social();
    }

    @Test
    void 기간_안_세션을_10분_미만까지_시작_시각과_함께_돌려준다() {
        long userId = insertUser();
        insertSession(userId, DAY, at(1), at(2), 599);
        insertSession(userId, DAY, at(3), at(4), 1200);
        insertSession(userId, DAY.plusDays(1), at(25), at(26), 900);

        assertThat(factsOf(userId))
                .extracting(SessionFact::statDate, SessionFact::startedAt, SessionFact::focusSec)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DAY, at(1), 599),
                        org.assertj.core.groups.Tuple.tuple(DAY, at(3), 1200));
    }

    @Test
    void 일시정지_이벤트만_센다() {
        long userId = insertUser();
        long sessionId = insertSession(userId, at(1), at(3));
        insertEvent(sessionId, "PAUSE");
        insertEvent(sessionId, "PAUSE");
        insertEvent(sessionId, "PHONE");

        assertThat(factsOf(userId).getFirst().pauseCount()).isEqualTo(2);
    }

    @Test
    void 룸_참여구간_안의_세션은_소셜이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(0), at(4));

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 룸_참여가_없는_세션은_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 부분만_겹쳐도_소셜이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(2), at(5));

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 아직_진행_중인_참여도_소셜이다() {
        // left_at NULL은 "지금 방에 있음"이다 (BY-626) — 밤새 방에 있던 유저의 어제 세션도 소셜이다
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(2), null);

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 세션_종료_후_시작된_진행_중_참여는_소셜이_아니다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(5), null);

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 겹치지_않는_참여는_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(5), at(6));

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 다른_유저의_참여는_소셜로_치지_않는다() {
        long owner = insertUser();
        long other = insertUser();
        insertSession(owner, at(1), at(3));
        insertParticipation(insertRoom(other), other, at(0), at(4));

        assertThat(socialOf(owner)).isFalse();
    }
}
```

- [ ] **Step 2: 리포트 사실 조회 테스트를 쓴다**

`src/test/java/project/study/metrics/report/ReportFactQueryIntegrationTest.java`:

```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.dday.service.DdayMetricsService;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.metrics.report.fact.UserFact;
import project.study.room.service.RoomMetricsService;
import project.study.subject.service.SubjectMetricsService;
import project.study.user.service.UserService;

/** 리포트 v2가 user·room·subject·dday 도메인에서 읽는 사실 (BY-904). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportFactQueryIntegrationTest {

    private static final Instant T0 = Instant.parse("2020-03-01T00:00:00Z");

    @Autowired
    private UserService userService;

    @Autowired
    private RoomMetricsService roomMetricsService;

    @Autowired
    private SubjectMetricsService subjectMetricsService;

    @Autowired
    private DdayMetricsService ddayMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser(Instant createdAt, String nickname, String category, String goal) {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, nickname, category, goal, created_at, updated_at) "
                        + "values ('DEVICE', ?, ?, ?, ?, ?, now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                category,
                goal,
                createdAt == null ? null : Timestamp.from(createdAt));
    }

    private long insertUser(Instant createdAt) {
        return insertUser(createdAt, null, null, null);
    }

    private long insertClosedRoom(long createdBy) {
        return jdbcTemplate.queryForObject(
                "insert into rooms (invite_code, created_by, created_at, closed_at, close_reason) "
                        + "values ('0000', ?, now(), now(), 'LAST_LEFT') returning id",
                Long.class,
                createdBy);
    }

    private void insertParticipation(long roomId, long userId, Instant joinedAt) {
        jdbcTemplate.update(
                "insert into room_participations (room_id, user_id, reserved_at, stomp_confirmed, joined_at, left_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                roomId,
                userId,
                Timestamp.from(T0),
                joinedAt != null,
                joinedAt == null ? null : Timestamp.from(joinedAt),
                Timestamp.from(T0.plusSeconds(86_400 * 30L)));
    }

    private long insertSubject(long userId, Instant createdAt, Instant deletedAt) {
        return jdbcTemplate.queryForObject(
                "insert into study_subject (user_id, name, created_at, updated_at, deleted_at) "
                        + "values (?, '과목', ?, ?, ?) returning id",
                Long.class,
                userId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                deletedAt == null ? null : Timestamp.from(deletedAt));
    }

    private void insertTask(long subjectId, Instant doneAt) {
        jdbcTemplate.update(
                "insert into study_task (subject_id, name, done_at) values (?, '할 일', ?)",
                subjectId,
                doneAt == null ? null : Timestamp.from(doneAt));
    }

    @Test
    void 기준_시각_뒤_가입자와_따로_지정한_유저를_가입_정보와_함께_돌려준다() {
        long recent = insertUser(T0.plusSeconds(10), "새내기", "CSAT", "수능 1등급");
        long old = insertUser(T0.minusSeconds(86_400), "옛유저", null, null);
        long untouched = insertUser(T0.minusSeconds(86_400));

        List<UserFact> users = userService.findReportUsers(T0, Set.of(old));

        assertThat(users).extracting(UserFact::userId).contains(recent, old).doesNotContain(untouched);
        assertThat(users)
                .filteredOn(user -> user.userId() == recent)
                .singleElement()
                .isEqualTo(new UserFact(recent, "새내기", T0.plusSeconds(10), "CSAT", "수능 1등급"));
    }

    @Test
    void 가입_시각이_없는_유저는_날짜를_매길_수_없어_뺀다() {
        long noCreatedAt = insertUser(null);

        assertThat(userService.findReportUsers(T0, Set.of(noCreatedAt)))
                .extracting(UserFact::userId)
                .doesNotContain(noCreatedAt);
    }

    @Test
    void 기간_안에_확정된_방_참여만_센다() {
        long userId = insertUser(T0);
        long room = insertClosedRoom(userId);
        insertParticipation(room, userId, T0);
        insertParticipation(room, userId, T0.plusSeconds(3600));
        insertParticipation(room, userId, T0.plusSeconds(7200)); // 끝 경계 — 빠진다
        insertParticipation(room, userId, null); // 확정 안 된 예약

        List<UserEventFact> joins = roomMetricsService.findJoins(T0, T0.plusSeconds(7200));

        assertThat(joins)
                .filteredOn(join -> join.userId() == userId)
                .extracting(UserEventFact::at)
                .containsExactlyInAnyOrder(T0, T0.plusSeconds(3600));
    }

    @Test
    void 지운_과목도_생성_시각과_함께_돌려준다() {
        long userId = insertUser(T0);
        long otherUser = insertUser(T0);
        insertSubject(userId, T0, null);
        insertSubject(userId, T0.plusSeconds(60), T0.plusSeconds(120));
        insertSubject(otherUser, T0, null);

        assertThat(subjectMetricsService.findSubjectsOf(List.of(userId)))
                .containsExactlyInAnyOrder(
                        new SubjectFact(userId, T0, null),
                        new SubjectFact(userId, T0.plusSeconds(60), T0.plusSeconds(120)));
        assertThat(subjectMetricsService.findSubjectsOf(List.of())).isEmpty();
    }

    @Test
    void 기간_안에_완료한_할_일을_과목_주인_유저로_돌려준다() {
        long userId = insertUser(T0);
        long subjectId = insertSubject(userId, T0, null);
        insertTask(subjectId, T0.plusSeconds(10));
        insertTask(subjectId, T0.plusSeconds(86_400)); // 기간 밖
        insertTask(subjectId, null); // 안 끝남

        assertThat(subjectMetricsService.findTasksDone(T0, T0.plusSeconds(3600)))
                .filteredOn(done -> done.userId() == userId)
                .containsExactly(new UserEventFact(userId, T0.plusSeconds(10)));
    }

    @Test
    void D_day는_만든_시각으로_돌려준다() {
        long userId = insertUser(T0);
        jdbcTemplate.update(
                "insert into user_dday (user_id, title, target_date, created_at, updated_at) values (?, '시험', ?, ?, ?)",
                userId,
                java.sql.Date.valueOf("2020-12-01"),
                Timestamp.from(T0),
                Timestamp.from(T0));

        assertThat(ddayMetricsService.findDdaysOf(List.of(userId))).containsExactly(new UserEventFact(userId, T0));
        assertThat(ddayMetricsService.findDdaysOf(List.of())).isEmpty();
    }
}
```

- [ ] **Step 3: Amplitude 조회 테스트를 쓴다**

`src/test/java/project/study/metrics/amplitude/AmplitudeQueryServiceIntegrationTest.java`:

```java
package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;

/** 리포트가 읽는 Amplitude 적재 데이터 (BY-904). 적재 테스트가 테이블을 비우므로 여기선 트랜잭션 롤백으로 정리한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AmplitudeQueryServiceIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2020, 4, 1);
    private static final long USER = 9_000_001L;
    private static final long OTHER = 9_000_002L;

    @Autowired
    private AmplitudeQueryService amplitudeQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void insertCount(long userId, LocalDate date, String key, int count) {
        jdbcTemplate.update(
                "insert into amplitude_daily_event (user_id, event_date, event_key, count) values (?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(date),
                key,
                count);
    }

    @Test
    void 기간과_키로_거른_일별_횟수를_돌려준다() {
        insertCount(USER, DAY, "app_launched", 3);
        insertCount(USER, DAY, "Page Viewed", 9);
        insertCount(USER, DAY.plusDays(5), "app_launched", 1);

        List<AmplitudeDailyCount> counts = amplitudeQueryService.findCounts(DAY, DAY.plusDays(1), Set.of("app_launched"));

        assertThat(counts)
                .filteredOn(count -> count.userId() == USER)
                .containsExactly(new AmplitudeDailyCount(USER, DAY, "app_launched", 3));
        assertThat(amplitudeQueryService.findCounts(DAY, DAY, Set.of())).isEmpty();
    }

    @Test
    void 요청한_유저의_속성만_돌려준다() {
        jdbcTemplate.update(
                "insert into amplitude_user_attribute (user_id, utm_source, ad_click, os_name, first_seen_at, last_seen_at) "
                        + "values (?, 'instagram', true, 'ios', now(), now()), (?, null, false, null, now(), now())",
                USER,
                OTHER);

        assertThat(amplitudeQueryService.findAttributes(List.of(USER)))
                .containsExactlyEntriesOf(java.util.Map.of(USER, new AmplitudeAttribute("instagram", true, "ios")));
        assertThat(amplitudeQueryService.findAttributes(List.of())).isEmpty();
    }

    @Test
    void 처리_기록이_있는_날만_수집일이다() {
        jdbcTemplate.update(
                "insert into amplitude_export_window (window_date, event_count, skipped) values (?, 1, 0)",
                java.sql.Date.valueOf(DAY));

        assertThat(amplitudeQueryService.collectedDates(DAY.minusDays(1), DAY.plusDays(1))).containsExactly(DAY);
    }
}
```

- [ ] **Step 4: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.studysession.SessionFactQueryIntegrationTest" --tests "project.study.metrics.report.ReportFactQueryIntegrationTest" --tests "project.study.metrics.amplitude.AmplitudeQueryServiceIntegrationTest"`
Expected: FAIL — `cannot find symbol: class SessionFact` 등 컴파일 오류

- [ ] **Step 5: 사실 레코드를 만든다**

`src/main/java/project/study/metrics/report/fact/UserFact.java`:
```java
package project.study.metrics.report.fact;

import java.time.Instant;

/** 리포트가 읽는 유저 한 명의 가입 정보 — user 도메인이 채운다 (BY-904). */
public record UserFact(long userId, String nickname, Instant createdAt, String category, String goal) {}
```

`src/main/java/project/study/metrics/report/fact/SessionFact.java`:
```java
package project.study.metrics.report.fact;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 세션 한 건 — studysession 도메인이 채운다. 자정 분할 조각은 각각 한 건이다(ADR-0012 결정 3).
 *
 * @param pauseCount PAUSE 상태 이벤트 수
 * @param social 세션 구간이 그 유저의 방 참여 구간과 겹치는지
 */
public record SessionFact(
        long userId, LocalDate statDate, Instant startedAt, int focusSec, int pauseCount, boolean social) {}
```

`src/main/java/project/study/metrics/report/fact/UserEventFact.java`:
```java
package project.study.metrics.report.fact;

import java.time.Instant;

/** 유저가 무언가를 한 시각 하나(방 참여·할 일 완료·D-day 설정) — 각 도메인이 채운다 (BY-904). */
public record UserEventFact(long userId, Instant at) {}
```

`src/main/java/project/study/metrics/report/fact/SubjectFact.java`:
```java
package project.study.metrics.report.fact;

import java.time.Instant;

/** 과목 하나의 생성·삭제 시각 — subject 도메인이 채운다. 지운 과목도 첫 주 등록 수에 센다 (BY-904). */
public record SubjectFact(long userId, Instant createdAt, Instant deletedAt) {}
```

- [ ] **Step 6: 세션 사실 조회를 만든다**

`src/main/java/project/study/studysession/repository/SessionFactQueries.java`:
```java
package project.study.studysession.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.fact.SessionFact;

/** 일일 리포트 v2가 읽는 세션 사실 (BY-904). 쓰기 흐름 쿼리와 섞이지 않게 StudySessionRepository와 나눈다. */
@Repository
@RequiredArgsConstructor
public class SessionFactQueries {

    private final JdbcClient jdbc;

    // 소셜 = 세션 [started_at, ended_at]이 그 유저의 방 참여 [joined_at, left_at]와 조금이라도 겹침. left_at NULL은 진행 중
    // 참여라 now()까지로 본다(BY-626). joined_at NULL(확정 없는 예약)은 비교식이 NULL이라 빠진다. room 엔티티를 참조하지
    // 않도록 테이블명으로만 조인한다. status_event(session_id)에 인덱스가 있어 세션별 일시정지 수는 인덱스로 센다
    public List<SessionFact> findBetween(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT s.user_id, s.stat_date, s.started_at, coalesce(s.focus_sec, 0) AS focus_sec,
                            (SELECT count(*) FROM status_event e
                             WHERE e.session_id = s.id AND e.status = 'PAUSE') AS pause_count,
                            EXISTS (
                                SELECT 1 FROM room_participations rp
                                WHERE rp.user_id = s.user_id
                                  AND rp.joined_at < s.ended_at
                                  AND s.started_at < coalesce(rp.left_at, now())
                            ) AS social
                        FROM study_session s
                        WHERE s.stat_date BETWEEN :from AND :to
                        ORDER BY s.user_id, s.started_at""")
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new SessionFact(
                        rs.getLong("user_id"),
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("focus_sec"),
                        rs.getInt("pause_count"),
                        rs.getBoolean("social")))
                .list();
    }
}
```

`StudySessionMetricsService`에 필드와 메서드를 더한다(옛 메서드는 Task 10에서 지운다):
```java
    private final SessionFactQueries sessionFactQueries;

    /** 일일 리포트 v2 — 기간(양끝 포함) 안 모든 세션을 일시정지 수·소셜 여부와 함께 (BY-904). */
    @Transactional(readOnly = true)
    public List<SessionFact> findSessionFacts(LocalDate from, LocalDate to) {
        return sessionFactQueries.findBetween(from, to);
    }
```
import: `project.study.metrics.report.fact.SessionFact`, `project.study.studysession.repository.SessionFactQueries`.

- [ ] **Step 7: 유저 조회를 만든다**

`UserRepository`에 추가:
```java
    List<User> findByCreatedAtGreaterThanEqual(Instant from);
```

`UserService`에 추가(import `java.util.Collection`, `java.util.LinkedHashMap`, `java.util.Map`, `project.study.metrics.report.fact.UserFact`):
```java
    /**
     * 일일 리포트 v2 대상 유저 — {@code signedUpFrom} 이후 가입자와, 그 전에 가입했지만 {@code alsoUserIds}에 든 유저(기간 안에
     * 공부한 사람). 가입 시각이 없는 옛 행은 날짜를 매길 수 없어 뺀다 (BY-904).
     */
    @Transactional(readOnly = true)
    public List<UserFact> findReportUsers(Instant signedUpFrom, Collection<Long> alsoUserIds) {
        Map<Long, User> users = new LinkedHashMap<>();
        userRepository.findByCreatedAtGreaterThanEqual(signedUpFrom).forEach(user -> users.put(user.getId(), user));
        List<Long> rest = alsoUserIds.stream()
                .filter(id -> !users.containsKey(id))
                .distinct()
                .toList();
        userRepository.findAllById(rest).forEach(user -> users.put(user.getId(), user));
        return users.values().stream()
                .filter(user -> user.getCreatedAt() != null)
                .map(user -> new UserFact(
                        user.getId(), user.getNickname(), user.getCreatedAt(), user.getCategory(), user.getGoal()))
                .toList();
    }
```

- [ ] **Step 8: 방·과목·D-day 조회를 만든다**

`src/main/java/project/study/room/repository/RoomMetricsQueries.java`:
```java
package project.study.room.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.fact.UserEventFact;

/** 일일 리포트 v2가 읽는 방 참여 사실 (BY-904). 쓰기·락 경로인 RoomParticipationRepository와 나눈다. */
@Repository
@RequiredArgsConstructor
public class RoomMetricsQueries {

    private final JdbcClient jdbc;

    /** 확정된 참여(joined_at 있음) 중 {@code [from, to)}에 들어온 것. 다시 들어오면 새 행이라 한 번씩 센다. */
    public List<UserEventFact> findJoinsBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT user_id, joined_at FROM room_participations
                        WHERE joined_at >= :from AND joined_at < :to""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> new UserEventFact(
                        rs.getLong("user_id"),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
```

`src/main/java/project/study/room/service/RoomMetricsService.java`:
```java
package project.study.room.service;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.report.fact.UserEventFact;
import project.study.room.repository.RoomMetricsQueries;

/** 지표 집계 전용 — 화면용 RoomService와 나눈다 (BY-904). */
@Service
@RequiredArgsConstructor
public class RoomMetricsService {

    private final RoomMetricsQueries roomMetricsQueries;

    @Transactional(readOnly = true)
    public List<UserEventFact> findJoins(Instant from, Instant to) {
        return roomMetricsQueries.findJoinsBetween(from, to);
    }
}
```

`src/main/java/project/study/subject/repository/SubjectMetricsQueries.java`:
```java
package project.study.subject.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;

/** 일일 리포트 v2가 읽는 과목·할 일 사실 (BY-904). */
@Repository
@RequiredArgsConstructor
public class SubjectMetricsQueries {

    private final JdbcClient jdbc;

    /** 유저들의 과목 전부(지운 것 포함). 빈 목록이면 IN ()이 문법 오류라 바로 돌려준다. */
    public List<SubjectFact> findSubjectsOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT user_id, created_at, deleted_at FROM study_subject WHERE user_id IN (:ids)")
                .param("ids", userIds)
                .query((rs, rowNum) ->
                        new SubjectFact(rs.getLong("user_id"), instant(rs, "created_at"), instant(rs, "deleted_at")))
                .list();
    }

    /** {@code [from, to)}에 완료한 할 일을 과목 주인 유저로. 지운 할 일도 완료 기록이라 센다. */
    public List<UserEventFact> findTasksDoneBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT s.user_id, t.done_at FROM study_task t
                        JOIN study_subject s ON s.id = t.subject_id
                        WHERE t.done_at >= :from AND t.done_at < :to""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> new UserEventFact(rs.getLong("user_id"), instant(rs, "done_at")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
```

`src/main/java/project/study/subject/service/SubjectMetricsService.java`:
```java
package project.study.subject.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.subject.repository.SubjectMetricsQueries;

/** 지표 집계 전용 — 화면용 StudySubjectService와 나눈다 (BY-904). */
@Service
@RequiredArgsConstructor
public class SubjectMetricsService {

    private final SubjectMetricsQueries subjectMetricsQueries;

    @Transactional(readOnly = true)
    public List<SubjectFact> findSubjectsOf(Collection<Long> userIds) {
        return subjectMetricsQueries.findSubjectsOf(userIds);
    }

    @Transactional(readOnly = true)
    public List<UserEventFact> findTasksDone(Instant from, Instant to) {
        return subjectMetricsQueries.findTasksDoneBetween(from, to);
    }
}
```

`DdayRepository`에 추가(import `java.util.Collection`, `java.util.List`):
```java
    List<Dday> findByUserIdIn(Collection<Long> userIds);
```

`src/main/java/project/study/dday/service/DdayMetricsService.java`:
```java
package project.study.dday.service;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.dday.repository.DdayRepository;
import project.study.metrics.report.fact.UserEventFact;

/** 지표 집계 전용 — D-day를 만든 시각 (BY-904). 지우고 다시 만들면 마지막 생성 시각만 남는다. */
@Service
@RequiredArgsConstructor
public class DdayMetricsService {

    private final DdayRepository ddayRepository;

    @Transactional(readOnly = true)
    public List<UserEventFact> findDdaysOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return ddayRepository.findByUserIdIn(userIds).stream()
                .map(dday -> new UserEventFact(dday.getUserId(), dday.getCreatedAt()))
                .toList();
    }
}
```

- [ ] **Step 9: Amplitude 조회를 만든다**

`src/main/java/project/study/metrics/amplitude/AmplitudeDailyCount.java`:
```java
package project.study.metrics.amplitude;

import java.time.LocalDate;

/** 유저 × KST 날짜 × 이벤트 키의 횟수 (V28 amplitude_daily_event 한 행). */
public record AmplitudeDailyCount(long userId, LocalDate date, String key, int count) {}
```

`src/main/java/project/study/metrics/amplitude/AmplitudeAttribute.java`:
```java
package project.study.metrics.amplitude;

/** 리포트가 쓰는 Amplitude 유저 속성 (V28 amplitude_user_attribute). 값이 없으면 null. */
public record AmplitudeAttribute(String utmSource, boolean adClick, String osName) {}
```

`src/main/java/project/study/metrics/amplitude/repository/AmplitudeQueryRepository.java`:
```java
package project.study.metrics.amplitude.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;

/** 리포트용 Amplitude 적재 데이터 읽기 (V28). 쓰기는 AmplitudeIngestRepository. */
@Repository
@RequiredArgsConstructor
public class AmplitudeQueryRepository {

    private final JdbcClient jdbc;

    public List<AmplitudeDailyCount> findCounts(LocalDate from, LocalDate to, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT user_id, event_date, event_key, count FROM amplitude_daily_event
                        WHERE event_date BETWEEN :from AND :to AND event_key IN (:keys)""")
                .param("from", from)
                .param("to", to)
                .param("keys", keys)
                .query((rs, rowNum) -> new AmplitudeDailyCount(
                        rs.getLong("user_id"),
                        rs.getObject("event_date", LocalDate.class),
                        rs.getString("event_key"),
                        rs.getInt("count")))
                .list();
    }

    public Map<Long, AmplitudeAttribute> findAttributes(Collection<Long> userIds) {
        Map<Long, AmplitudeAttribute> attributes = new HashMap<>();
        if (userIds.isEmpty()) {
            return attributes;
        }
        jdbc.sql("SELECT user_id, utm_source, ad_click, os_name FROM amplitude_user_attribute WHERE user_id IN (:ids)")
                .param("ids", userIds)
                .query(rs -> {
                    attributes.put(
                            rs.getLong("user_id"),
                            new AmplitudeAttribute(
                                    rs.getString("utm_source"), rs.getBoolean("ad_click"), rs.getString("os_name")));
                });
        return attributes;
    }
}
```

`src/main/java/project/study/metrics/amplitude/AmplitudeQueryService.java`:
```java
package project.study.metrics.amplitude;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;
import project.study.metrics.amplitude.repository.AmplitudeQueryRepository;

/** 일일 리포트가 읽는 Amplitude 횟수·속성·수집일 (스펙 §7.5, BY-904). */
@Service
@RequiredArgsConstructor
public class AmplitudeQueryService {

    private final AmplitudeQueryRepository queryRepository;
    private final AmplitudeIngestRepository ingestRepository;

    @Transactional(readOnly = true)
    public List<AmplitudeDailyCount> findCounts(LocalDate from, LocalDate to, Collection<String> keys) {
        return queryRepository.findCounts(from, to, keys);
    }

    @Transactional(readOnly = true)
    public Map<Long, AmplitudeAttribute> findAttributes(Collection<Long> userIds) {
        return queryRepository.findAttributes(userIds);
    }

    /** 창 처리 기록이 있는 KST 날짜 — 이 날의 Amplitude 값만 믿는다. */
    @Transactional(readOnly = true)
    public Set<LocalDate> collectedDates(LocalDate from, LocalDate to) {
        return ingestRepository.findProcessedDates(from, to);
    }
}
```

- [ ] **Step 10: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.studysession.SessionFactQueryIntegrationTest" --tests "project.study.metrics.report.ReportFactQueryIntegrationTest" --tests "project.study.metrics.amplitude.AmplitudeQueryServiceIntegrationTest"`
Expected: PASS (17 tests)

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/project/study/metrics/report/fact src/main/java/project/study/studysession src/main/java/project/study/user \
  src/main/java/project/study/room/repository/RoomMetricsQueries.java src/main/java/project/study/room/service/RoomMetricsService.java \
  src/main/java/project/study/subject/repository/SubjectMetricsQueries.java src/main/java/project/study/subject/service/SubjectMetricsService.java \
  src/main/java/project/study/dday src/main/java/project/study/metrics/amplitude \
  src/test/java/project/study/studysession/SessionFactQueryIntegrationTest.java \
  src/test/java/project/study/metrics/report/ReportFactQueryIntegrationTest.java \
  src/test/java/project/study/metrics/amplitude/AmplitudeQueryServiceIntegrationTest.java
git commit -m "feat: 일일 리포트가 읽을 도메인 사실 조회를 추가한다 (BY-904)"
```

---
### Task 2: 유저 타임라인과 리포트 데이터

**Files:**
- Create: `src/main/java/project/study/metrics/report/Kst.java`, `Rate.java`, `AmplitudeKeys.java`, `UserTimeline.java`, `ReportData.java`
- Test: `src/test/java/project/study/metrics/report/TimelineBuilder.java`(픽스처), `ReportDataFixture.java`(픽스처),
  `UserTimelineTest.java`, `RateTest.java`

**Interfaces:**
- Consumes: Task 1의 `UserFact`, `SessionFact`, `SubjectFact`, `AmplitudeDailyCount`, `AmplitudeAttribute`
- Produces:
  - `Kst.ZONE`, `Kst.date(Instant): LocalDate`, `Kst.hour(Instant): int`, `Kst.startOf(LocalDate): Instant`, `Kst.bandLabel(int startHour): String`("21~24시")
  - `record Rate(int numerator, int denominator)` — `of(int,int)`, `defined()`, `percent(): int`(half-up, 미정의면 예외), `ratio(): double`(미정의면 0),
    `atLeastPercent(int percent): boolean`
  - `AmplitudeKeys` 상수 14개 + `ALL: Set<String>`, `APP_OPEN: Set<String>`
  - `UserTimeline(UserFact, List<SessionFact>, Map<Activity, List<Instant>>, List<SubjectFact>, List<AmplitudeDailyCount>, AmplitudeAttribute)`
    와 `enum Activity { ROOM_JOIN, TASK_DONE, DDAY_SET, SUBJECT_CREATED }`, 메서드: `userId() nickname() signupAt() signupDate() category()
    goal() attribute(): Optional<AmplitudeAttribute>`, `day(int)`, `studied(LocalDate)`, `focusSec(LocalDate)`, `focusSec(LocalDate, LocalDate)`,
    `studyDays(LocalDate, LocalDate)`, `longestRun(LocalDate, LocalDate, int minDailyFocusSec)`, `streakEndingAt(LocalDate)`,
    `lastStudyDay(LocalDate): Optional<LocalDate>`, `sessions(LocalDate, LocalDate): List<SessionFact>`,
    `activityCount(Activity, LocalDate, LocalDate)`, `amplitudeCount(String, LocalDate, LocalDate)`, `amplitudeDays(String, LocalDate, LocalDate)`,
    `liveSubjectsAt(Instant)`, `focusStartedWithin(Duration)`
  - `record ReportData(LocalDate anchor, List<UserTimeline> users, Set<LocalDate> amplitudeDates, Set<Long> appOpenedOnAnchor)` —
    `signedUpBetween(LocalDate, LocalDate)`(가입 시각 → id 순), `amplitudeCollected(LocalDate, LocalDate)`, `studiedOn(LocalDate)`
  - 테스트 픽스처 `TimelineBuilder.user(long id, LocalDate signupDate)`와 `ReportDataFixture.data(LocalDate anchor, boolean amplitude, UserTimeline...)`
    (아래 코드) — Task 4~8 테스트가 쓴다

- [ ] **Step 1: 픽스처를 만든다**

`src/test/java/project/study/metrics/report/TimelineBuilder.java`:
```java
package project.study.metrics.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserFact;

/** 테스트용 — 유저 한 명의 사실을 KST 날짜·시각 단위로 쌓는다. 가입은 기본 12시, 공부는 기본 20시 싱글 세션. */
public final class TimelineBuilder {

    private final long userId;
    private Instant signupAt;
    private String nickname;
    private String category;
    private String goal;
    private final List<SessionFact> sessions = new ArrayList<>();
    private final Map<Activity, List<Instant>> activities = new EnumMap<>(Activity.class);
    private final List<SubjectFact> subjects = new ArrayList<>();
    private final List<AmplitudeDailyCount> counts = new ArrayList<>();
    private AmplitudeAttribute attribute;

    private TimelineBuilder(long userId, Instant signupAt) {
        this.userId = userId;
        this.signupAt = signupAt;
        this.nickname = "유저" + userId;
    }

    public static TimelineBuilder user(long userId, LocalDate signupDate) {
        return new TimelineBuilder(userId, at(signupDate, 12));
    }

    public static Instant at(LocalDate date, int hour) {
        return date.atStartOfDay(Kst.ZONE).toInstant().plus(Duration.ofHours(hour));
    }

    public TimelineBuilder signupHour(int hour) {
        this.signupAt = at(Kst.date(signupAt), hour);
        return this;
    }

    public TimelineBuilder signupAt(Instant instant) {
        this.signupAt = instant;
        return this;
    }

    public TimelineBuilder profile(String nickname, String category, String goal) {
        this.nickname = nickname;
        this.category = category;
        this.goal = goal;
        return this;
    }

    /** 가입 n일 뒤(KST) 20시에 minutes분 순공한 싱글 세션. */
    public TimelineBuilder studyOnDay(int dayOffset, int minutes) {
        LocalDate date = Kst.date(signupAt).plusDays(dayOffset);
        return session(date, 20, minutes, 0, false);
    }

    /** 가입 n일 뒤마다 30분씩. */
    public TimelineBuilder studyOnDays(int... dayOffsets) {
        for (int offset : dayOffsets) {
            studyOnDay(offset, 30);
        }
        return this;
    }

    public TimelineBuilder session(LocalDate statDate, int startHour, int minutes, int pauses, boolean social) {
        sessions.add(new SessionFact(userId, statDate, at(statDate, startHour), minutes * 60, pauses, social));
        return this;
    }

    public TimelineBuilder sessionAt(Instant startedAt, LocalDate statDate, int focusSec) {
        sessions.add(new SessionFact(userId, statDate, startedAt, focusSec, 0, false));
        return this;
    }

    public TimelineBuilder activity(Activity activity, int dayOffset, int times) {
        for (int i = 0; i < times; i++) {
            Instant when = at(Kst.date(signupAt).plusDays(dayOffset), 9).plusSeconds(i);
            activities.computeIfAbsent(activity, key -> new ArrayList<>()).add(when);
        }
        return this;
    }

    public TimelineBuilder subject(int createdDayOffset, Integer deletedDayOffset) {
        LocalDate signupDate = Kst.date(signupAt);
        Instant deletedAt = deletedDayOffset == null ? null : at(signupDate.plusDays(deletedDayOffset), 9);
        subjects.add(new SubjectFact(userId, at(signupDate.plusDays(createdDayOffset), 9), deletedAt));
        return this;
    }

    public TimelineBuilder amp(String key, int dayOffset, int count) {
        counts.add(new AmplitudeDailyCount(userId, Kst.date(signupAt).plusDays(dayOffset), key, count));
        return this;
    }

    public TimelineBuilder ampOn(String key, LocalDate date, int count) {
        counts.add(new AmplitudeDailyCount(userId, date, key, count));
        return this;
    }

    public TimelineBuilder attribute(String utmSource, boolean adClick, String osName) {
        this.attribute = new AmplitudeAttribute(utmSource, adClick, osName);
        return this;
    }

    public UserTimeline build() {
        UserFact fact = new UserFact(userId, nickname, signupAt, category, goal);
        return new UserTimeline(fact, sessions, activities, subjects, counts, attribute);
    }
}
```

`src/test/java/project/study/metrics/report/ReportDataFixture.java`:
```java
package project.study.metrics.report;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 테스트용 — 타임라인 목록을 ReportData로. amplitude=true면 [A−61, A] 전부 수집된 것으로 본다. */
public final class ReportDataFixture {

    private ReportDataFixture() {}

    public static ReportData data(LocalDate anchor, boolean amplitude, UserTimeline... users) {
        Set<LocalDate> dates = amplitude
                ? anchor.minusDays(61).datesUntil(anchor.plusDays(1)).collect(Collectors.toSet())
                : Set.of();
        Set<Long> appOpened = new HashSet<>();
        for (UserTimeline user : users) {
            if (AmplitudeKeys.APP_OPEN.stream().anyMatch(key -> user.amplitudeCount(key, anchor, anchor) > 0)) {
                appOpened.add(user.userId());
            }
        }
        return new ReportData(anchor, List.of(users), dates, appOpened);
    }
}
```

- [ ] **Step 2: 타임라인 테스트를 쓴다**

`src/test/java/project/study/metrics/report/UserTimelineTest.java`:
```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.at;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.UserTimeline.Activity;

class UserTimelineTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 29);

    @Test
    void 가입일은_KST_날짜다() {
        // 2026-09-28T15:30Z = 9/29 00:30 KST
        UserTimeline timeline = user(1, D0).signupAt(java.time.Instant.parse("2026-09-28T15:30:00Z")).build();

        assertThat(timeline.signupDate()).isEqualTo(D0);
        assertThat(timeline.day(7)).isEqualTo(D0.plusDays(7));
    }

    @Test
    void 공부한_날은_10분_이상_세션이_있는_날이고_하루_순공은_모든_세션의_합이다() {
        UserTimeline timeline = user(1, D0).studyOnDay(0, 9).studyOnDay(0, 5).studyOnDay(1, 10).build();

        assertThat(timeline.studied(D0)).isFalse();
        assertThat(timeline.focusSec(D0)).isEqualTo(14 * 60);
        assertThat(timeline.studied(D0.plusDays(1))).isTrue();
        assertThat(timeline.studyDays(D0, D0.plusDays(6))).isEqualTo(1);
    }

    @Test
    void 자정_분할로_조각난_세션은_각_조각이_10분_미만이면_공부한_날이_아니다() {
        // ADR-0012 결정 3 — 23:54~00:06 12분 세션이 6분·6분 두 조각이면 어느 날도 공부한 날이 아니다
        UserTimeline timeline = user(1, D0)
                .sessionAt(at(D0, 23).plusSeconds(54 * 60), D0, 360)
                .sessionAt(at(D0.plusDays(1), 0), D0.plusDays(1), 360)
                .build();

        assertThat(timeline.studyDays(D0, D0.plusDays(1))).isZero();
    }

    @Test
    void 가장_긴_연속은_하루_순공_하한을_넘긴_공부한_날만_잇는다() {
        UserTimeline timeline = user(1, D0)
                .studyOnDay(0, 60)
                .studyOnDay(1, 20)
                .studyOnDay(2, 60)
                .studyOnDay(3, 60)
                .studyOnDay(5, 60)
                .build();

        assertThat(timeline.longestRun(D0, D0.plusDays(6), 600)).isEqualTo(4);
        assertThat(timeline.longestRun(D0, D0.plusDays(6), 3600)).isEqualTo(2);
        assertThat(timeline.longestRun(D0.plusDays(4), D0.plusDays(4), 600)).isZero();
    }

    @Test
    void 연속과_마지막_공부일은_기준일부터_거꾸로_센다() {
        UserTimeline timeline = user(1, D0).studyOnDays(0, 2, 3, 4).build();

        assertThat(timeline.streakEndingAt(D0.plusDays(4))).isEqualTo(3);
        assertThat(timeline.streakEndingAt(D0.plusDays(5))).isZero();
        assertThat(timeline.lastStudyDay(D0.plusDays(10))).contains(D0.plusDays(4));
        assertThat(timeline.lastStudyDay(D0.plusDays(1))).contains(D0);
        assertThat(user(2, D0).build().lastStudyDay(D0)).isEmpty();
    }

    @Test
    void 활동과_Amplitude_횟수는_KST_날짜_범위로_센다() {
        UserTimeline timeline = user(1, D0)
                .activity(Activity.ROOM_JOIN, 0, 2)
                .activity(Activity.ROOM_JOIN, 7, 1)
                .amp("app_launched", 0, 3)
                .amp("app_launched", 2, 1)
                .amp("app_launched", 9, 5)
                .build();

        assertThat(timeline.activityCount(Activity.ROOM_JOIN, D0, D0.plusDays(6))).isEqualTo(2);
        assertThat(timeline.amplitudeCount("app_launched", D0, D0.plusDays(6))).isEqualTo(4);
        assertThat(timeline.amplitudeDays("app_launched", D0, D0.plusDays(6))).isEqualTo(2);
        assertThat(timeline.amplitudeCount("planner_opened", D0, D0.plusDays(6))).isZero();
    }

    @Test
    void 과목은_만든_날을_활동으로_세고_그_시각에_살아있는_과목_수도_센다() {
        UserTimeline timeline = user(1, D0).subject(0, null).subject(1, 3).subject(8, null).build();

        assertThat(timeline.activityCount(Activity.SUBJECT_CREATED, D0, D0.plusDays(6))).isEqualTo(2);
        assertThat(timeline.liveSubjectsAt(at(D0.plusDays(2), 0))).isEqualTo(2);
        assertThat(timeline.liveSubjectsAt(at(D0.plusDays(9), 0))).isEqualTo(2);
    }

    @Test
    void 가입_3시간_안에_시작한_세션의_순공을_자정을_넘어서도_더한다() {
        // 23시 가입 → 00:30 시작 세션은 다음 날 stat_date지만 3시간 안이다
        UserTimeline timeline = user(1, D0)
                .signupHour(23)
                .sessionAt(at(D0.plusDays(1), 0).plusSeconds(1800), D0.plusDays(1), 700)
                .sessionAt(at(D0.plusDays(1), 2).plusSeconds(1), D0.plusDays(1), 900)
                .build();

        assertThat(timeline.focusStartedWithin(Duration.ofHours(3))).isEqualTo(700);
    }
}
```

`src/test/java/project/study/metrics/report/RateTest.java`:
```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateTest {

    @Test
    void 퍼센트는_정수_반올림이다() {
        assertThat(Rate.of(8, 14).percent()).isEqualTo(57);
        assertThat(Rate.of(1, 8).percent()).isEqualTo(13); // 12.5 → 13
        assertThat(Rate.of(0, 3).percent()).isZero();
    }

    @Test
    void 분모가_0이면_정의되지_않는다() {
        Rate empty = Rate.of(0, 0);

        assertThat(empty.defined()).isFalse();
        assertThat(empty.ratio()).isZero();
        assertThatThrownBy(empty::percent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 몇_퍼센트_이상인지_정수로_비교한다() {
        assertThat(Rate.of(3, 10).atLeastPercent(30)).isTrue();
        assertThat(Rate.of(29, 100).atLeastPercent(30)).isFalse();
        assertThat(Rate.of(0, 0).atLeastPercent(30)).isFalse();
    }
}
```

- [ ] **Step 3: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.UserTimelineTest" --tests "project.study.metrics.report.RateTest"`
Expected: FAIL — `cannot find symbol: class UserTimeline`

- [ ] **Step 4: 공통 도구를 만든다**

`src/main/java/project/study/metrics/report/Kst.java`:
```java
package project.study.metrics.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 리포트의 날짜·시각은 모두 KST다. */
public final class Kst {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Kst() {}

    public static LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    public static int hour(Instant instant) {
        return instant.atZone(ZONE).getHour();
    }

    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    /** 3시간 구간 라벨 — 21 → "21~24시". */
    public static String bandLabel(int startHour) {
        return "%02d~%02d시".formatted(startHour, startHour + 3);
    }
}
```

`src/main/java/project/study/metrics/report/Rate.java`:
```java
package project.study.metrics.report;

/** 분자/분모. 메시지에는 "57% (8/14)"로, 분모가 0이면 "—"로 쓴다. */
public record Rate(int numerator, int denominator) {

    public static Rate of(int numerator, int denominator) {
        return new Rate(numerator, denominator);
    }

    public boolean defined() {
        return denominator > 0;
    }

    /** 정수 반올림(half-up). */
    public int percent() {
        if (!defined()) {
            throw new IllegalStateException("분모가 0인 비율에는 퍼센트가 없다");
        }
        return (int) Math.round(100.0 * numerator / denominator);
    }

    public double ratio() {
        return defined() ? (double) numerator / denominator : 0;
    }

    /** 실수 오차 없이 "percent% 이상"을 비교한다. */
    public boolean atLeastPercent(int percent) {
        return defined() && numerator * 100L >= (long) percent * denominator;
    }
}
```

`src/main/java/project/study/metrics/report/AmplitudeKeys.java`:
```java
package project.study.metrics.report;

import java.util.Set;

/** 리포트가 읽는 Amplitude 이벤트 키 (스펙 §5·§7.3). 속성값 키(`:on`·`:landscape`)는 적재가 만든다. */
public final class AmplitudeKeys {

    public static final String APP_LAUNCHED = "app_launched";
    public static final String APP_FOREGROUNDED = "app_foregrounded";
    public static final String AMBIENT_SOUND = "ambient_sound_changed";
    public static final String PLANNER = "planner_opened";
    public static final String INVITE_SHARED = "invite_shared";
    public static final String GUIDE_FINISHED = "guide_finished";
    public static final String RANKING = "ranking_board_viewed";
    public static final String DISTRACTED = "study_session_distracted";
    public static final String SIMPLE_MODE_ON = "session_simple_mode_toggled:on";
    public static final String LANDSCAPE = "session_orientation_changed:landscape";
    public static final String CAMERA_ON = "social_room_camera_toggled:on";
    public static final String INVITE_OPENED = "invite_deep_link_opened";
    public static final String RECORDS_DATE = "records_date_selected";
    public static final String PUSH_OPENED = "push_notification_opened";

    /** "앱을 열었다"로 보는 키 — 어제 앱만 열고 공부 안 한 사람 수에 쓴다. */
    public static final Set<String> APP_OPEN = Set.of(APP_LAUNCHED, APP_FOREGROUNDED);

    public static final Set<String> ALL = Set.of(
            APP_LAUNCHED,
            APP_FOREGROUNDED,
            AMBIENT_SOUND,
            PLANNER,
            INVITE_SHARED,
            GUIDE_FINISHED,
            RANKING,
            DISTRACTED,
            SIMPLE_MODE_ON,
            LANDSCAPE,
            CAMERA_ON,
            INVITE_OPENED,
            RECORDS_DATE,
            PUSH_OPENED);

    private AmplitudeKeys() {}
}
```

- [ ] **Step 5: 타임라인과 리포트 데이터를 만든다**

`src/main/java/project/study/metrics/report/UserTimeline.java`:
```java
package project.study.metrics.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserFact;
import project.study.studysession.StudySessionThresholds;

/**
 * 유저 한 명의 리포트 기간 사실(일별 순공·공부한 날·세션·활동·Amplitude 횟수·속성). 분석기는 이 객체에만 묻는다.
 *
 * <p>공부한 날은 세션 단위로 판정한다 — 자정 분할 조각은 각각 10분을 넘어야 한다(ADR-0012 결정 3, 앱 스트릭과 같은 잣대).
 */
public final class UserTimeline {

    public enum Activity {
        ROOM_JOIN,
        TASK_DONE,
        DDAY_SET,
        SUBJECT_CREATED
    }

    private final UserFact user;
    private final LocalDate signupDate;
    private final List<SessionFact> sessions;
    private final Map<LocalDate, Integer> focusByDate = new HashMap<>();
    private final Set<LocalDate> studyDates = new HashSet<>();
    private final Map<Activity, List<LocalDate>> activityDates = new EnumMap<>(Activity.class);
    private final List<SubjectFact> subjects;
    private final Map<String, Map<LocalDate, Integer>> amplitude = new HashMap<>();
    private final AmplitudeAttribute attribute;

    public UserTimeline(
            UserFact user,
            List<SessionFact> sessions,
            Map<Activity, List<Instant>> activities,
            List<SubjectFact> subjects,
            List<AmplitudeDailyCount> amplitudeCounts,
            AmplitudeAttribute attribute) {
        this.user = user;
        this.signupDate = Kst.date(user.createdAt());
        this.sessions = sessions.stream()
                .sorted(Comparator.comparing(SessionFact::startedAt))
                .toList();
        for (SessionFact session : this.sessions) {
            focusByDate.merge(session.statDate(), session.focusSec(), Integer::sum);
            if (session.focusSec() >= StudySessionThresholds.MIN_STREAK_FOCUS_SEC) {
                studyDates.add(session.statDate());
            }
        }
        activities.forEach((activity, times) ->
                activityDates.put(activity, times.stream().map(Kst::date).toList()));
        this.subjects = List.copyOf(subjects);
        activityDates.put(
                Activity.SUBJECT_CREATED,
                subjects.stream().map(SubjectFact::createdAt).map(Kst::date).toList());
        for (AmplitudeDailyCount count : amplitudeCounts) {
            amplitude.computeIfAbsent(count.key(), key -> new HashMap<>()).merge(count.date(), count.count(), Integer::sum);
        }
        this.attribute = attribute;
    }

    public long userId() {
        return user.userId();
    }

    public String nickname() {
        return user.nickname();
    }

    public Instant signupAt() {
        return user.createdAt();
    }

    public LocalDate signupDate() {
        return signupDate;
    }

    public String category() {
        return user.category();
    }

    public String goal() {
        return user.goal();
    }

    public Optional<AmplitudeAttribute> attribute() {
        return Optional.ofNullable(attribute);
    }

    /** Dn = 가입일 + n일. */
    public LocalDate day(int n) {
        return signupDate.plusDays(n);
    }

    public boolean studied(LocalDate date) {
        return studyDates.contains(date);
    }

    public int focusSec(LocalDate date) {
        return focusByDate.getOrDefault(date, 0);
    }

    public int focusSec(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).mapToInt(this::focusSec).sum();
    }

    public int studyDays(LocalDate from, LocalDate to) {
        return (int) from.datesUntil(to.plusDays(1)).filter(this::studied).count();
    }

    /** 공부한 날이면서 하루 순공이 {@code minDailyFocusSec} 이상인 날이 이어진 가장 긴 일수. */
    public int longestRun(LocalDate from, LocalDate to, int minDailyFocusSec) {
        int longest = 0;
        int current = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            current = studied(date) && focusSec(date) >= minDailyFocusSec ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return longest;
    }

    /** {@code date}부터 거꾸로 센 연속 공부한 날. 그날 공부를 안 했으면 0. */
    public int streakEndingAt(LocalDate date) {
        int streak = 0;
        for (LocalDate day = date; studied(day); day = day.minusDays(1)) {
            streak++;
        }
        return streak;
    }

    public Optional<LocalDate> lastStudyDay(LocalDate onOrBefore) {
        return studyDates.stream().filter(date -> !date.isAfter(onOrBefore)).max(Comparator.naturalOrder());
    }

    public List<SessionFact> sessions(LocalDate from, LocalDate to) {
        return sessions.stream()
                .filter(session -> !session.statDate().isBefore(from) && !session.statDate().isAfter(to))
                .toList();
    }

    public int activityCount(Activity activity, LocalDate from, LocalDate to) {
        return (int) activityDates.getOrDefault(activity, List.of()).stream()
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .count();
    }

    public int amplitudeCount(String key, LocalDate from, LocalDate to) {
        return amplitude.getOrDefault(key, Map.of()).entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(from) && !entry.getKey().isAfter(to))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    public int amplitudeDays(String key, LocalDate from, LocalDate to) {
        return (int) amplitude.getOrDefault(key, Map.of()).entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(from) && !entry.getKey().isAfter(to))
                .filter(entry -> entry.getValue() > 0)
                .count();
    }

    /** {@code at} 시각에 살아 있던(만들어졌고 아직 안 지운) 과목 수. */
    public int liveSubjectsAt(Instant at) {
        return (int) subjects.stream()
                .filter(subject -> !subject.createdAt().isAfter(at))
                .filter(subject -> subject.deletedAt() == null || subject.deletedAt().isAfter(at))
                .count();
    }

    /** 가입 시각부터 {@code window} 안에 시작한 세션들의 순공 합 — "가입하고 바로 공부"(스펙 §4.1). */
    public int focusStartedWithin(Duration window) {
        Instant until = signupAt().plus(window);
        return sessions.stream()
                .filter(session -> !session.startedAt().isBefore(signupAt()) && session.startedAt().isBefore(until))
                .mapToInt(SessionFact::focusSec)
                .sum();
    }
}
```

`src/main/java/project/study/metrics/report/ReportData.java`:
```java
package project.study.metrics.report;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 리포트 한 번에 쓰는 사실 전체.
 *
 * @param anchor 기준일 A(발송일 KST 어제)
 * @param amplitudeDates Amplitude 창 처리 기록이 있는 날
 * @param appOpenedOnAnchor A에 앱을 연(`app_launched`·`app_foregrounded`) 유저 — 제외 유저는 뺀 값
 */
public record ReportData(
        LocalDate anchor, List<UserTimeline> users, Set<LocalDate> amplitudeDates, Set<Long> appOpenedOnAnchor) {

    private static final Comparator<UserTimeline> SIGNUP_ORDER =
            Comparator.comparing(UserTimeline::signupAt).thenComparing(UserTimeline::userId);

    /** 가입일이 {@code [from, to]}인 유저, 가입 시각 순. */
    public List<UserTimeline> signedUpBetween(LocalDate from, LocalDate to) {
        return users.stream()
                .filter(user -> !user.signupDate().isBefore(from) && !user.signupDate().isAfter(to))
                .sorted(SIGNUP_ORDER)
                .toList();
    }

    /** {@code [from, to]}의 모든 날에 Amplitude 창이 처리됐는지. */
    public boolean amplitudeCollected(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).allMatch(amplitudeDates::contains);
    }

    public List<UserTimeline> studiedOn(LocalDate date) {
        return users.stream().filter(user -> user.studied(date)).toList();
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.UserTimelineTest" --tests "project.study.metrics.report.RateTest"`
Expected: PASS (11 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/project/study/metrics/report src/test/java/project/study/metrics/report
git commit -m "feat: 리포트용 유저 타임라인을 추가한다 (BY-904)"
```

---
### Task 3: 리포트 데이터 로더

**Files:**
- Create: `src/main/java/project/study/metrics/report/ReportDataLoader.java`
- Test: `src/test/java/project/study/metrics/report/ReportDataLoaderTest.java`

**Interfaces:**
- Consumes: Task 1 서비스 메서드 전부, Task 2 `UserTimeline`·`ReportData`·`AmplitudeKeys`·`Kst`
- Produces: `ReportDataLoader.load(LocalDate anchor): ReportData` (빈 `@Component`, 생성자 마지막 인자 `@Value("${metrics.report.excluded-user-ids:}") Set<Long>`)

- [ ] **Step 1: 로더 테스트를 쓴다**

`src/test/java/project/study/metrics/report/ReportDataLoaderTest.java`:
```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.dday.service.DdayMetricsService;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.amplitude.AmplitudeQueryService;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.metrics.report.fact.UserFact;
import project.study.room.service.RoomMetricsService;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.subject.service.SubjectMetricsService;
import project.study.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class ReportDataLoaderTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final Instant FACT_FROM = Kst.startOf(A.minusDays(61));
    private static final Instant FACT_TO = Kst.startOf(A.plusDays(1));

    @Mock
    private UserService userService;

    @Mock
    private StudySessionMetricsService sessionMetricsService;

    @Mock
    private RoomMetricsService roomMetricsService;

    @Mock
    private SubjectMetricsService subjectMetricsService;

    @Mock
    private DdayMetricsService ddayMetricsService;

    @Mock
    private AmplitudeQueryService amplitudeQueryService;

    private ReportDataLoader loader(Set<Long> excluded) {
        return new ReportDataLoader(
                userService,
                sessionMetricsService,
                roomMetricsService,
                subjectMetricsService,
                ddayMetricsService,
                amplitudeQueryService,
                excluded);
    }

    private void stubEmptyExtras(Set<Long> userIds) {
        when(subjectMetricsService.findTasksDone(FACT_FROM, FACT_TO)).thenReturn(List.of());
        when(ddayMetricsService.findDdaysOf(userIds)).thenReturn(List.of());
        when(subjectMetricsService.findSubjectsOf(userIds)).thenReturn(List.of());
        when(amplitudeQueryService.collectedDates(A.minusDays(61), A)).thenReturn(Set.of(A));
    }

    @Test
    void 정해진_기간을_읽어_유저별_타임라인으로_묶는다() {
        Instant signup = Kst.startOf(A.minusDays(3)).plusSeconds(3600);
        when(sessionMetricsService.findSessionFacts(A.minusDays(61), A.plusDays(1)))
                .thenReturn(List.of(new SessionFact(1, A, Kst.startOf(A), 900, 0, false)));
        when(userService.findReportUsers(Kst.startOf(A.minusDays(68)), Set.of(1L)))
                .thenReturn(List.of(new UserFact(1, "곰", signup, "CSAT", null)));
        when(roomMetricsService.findJoins(FACT_FROM, FACT_TO))
                .thenReturn(List.of(new UserEventFact(1, Kst.startOf(A.minusDays(2)))));
        when(amplitudeQueryService.findCounts(A.minusDays(61), A, AmplitudeKeys.ALL))
                .thenReturn(List.of(
                        new AmplitudeDailyCount(1, A, "app_launched", 2),
                        new AmplitudeDailyCount(5, A, "app_foregrounded", 1),
                        new AmplitudeDailyCount(5, A.minusDays(1), "app_launched", 1)));
        when(amplitudeQueryService.findAttributes(Set.of(1L)))
                .thenReturn(Map.of(1L, new AmplitudeAttribute("instagram", true, "ios")));
        stubEmptyExtras(Set.of(1L));

        ReportData data = loader(Set.of()).load(A);

        assertThat(data.anchor()).isEqualTo(A);
        assertThat(data.amplitudeDates()).containsExactly(A);
        assertThat(data.appOpenedOnAnchor()).containsExactlyInAnyOrder(1L, 5L);
        assertThat(data.users()).singleElement().satisfies(user -> {
            assertThat(user.userId()).isEqualTo(1);
            assertThat(user.studied(A)).isTrue();
            assertThat(user.activityCount(Activity.ROOM_JOIN, A.minusDays(2), A.minusDays(2)))
                    .isEqualTo(1);
            assertThat(user.amplitudeCount("app_launched", A, A)).isEqualTo(2);
            assertThat(user.attribute()).contains(new AmplitudeAttribute("instagram", true, "ios"));
        });
    }

    @Test
    void 제외_유저는_세션_유저_앱_연_사람_어디에도_남지_않는다() {
        Instant signup = Kst.startOf(A.minusDays(1));
        when(sessionMetricsService.findSessionFacts(A.minusDays(61), A.plusDays(1)))
                .thenReturn(List.of(
                        new SessionFact(1, A, Kst.startOf(A), 900, 0, false),
                        new SessionFact(7, A, Kst.startOf(A), 900, 0, false)));
        when(userService.findReportUsers(Kst.startOf(A.minusDays(68)), Set.of(1L)))
                .thenReturn(List.of(new UserFact(1, "곰", signup, null, null), new UserFact(7, "운영자", signup, null, null)));
        when(roomMetricsService.findJoins(FACT_FROM, FACT_TO)).thenReturn(List.of());
        when(amplitudeQueryService.findCounts(any(), any(), any()))
                .thenReturn(List.of(new AmplitudeDailyCount(7, A, "app_launched", 1)));
        when(amplitudeQueryService.findAttributes(Set.of(1L))).thenReturn(Map.of());
        stubEmptyExtras(Set.of(1L));

        ReportData data = loader(Set.of(7L)).load(A);

        assertThat(data.users()).extracting(UserTimeline::userId).containsExactly(1L);
        assertThat(data.appOpenedOnAnchor()).isEmpty();
        verify(userService).findReportUsers(Kst.startOf(A.minusDays(68)), Set.of(1L));
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.ReportDataLoaderTest"`
Expected: FAIL — `cannot find symbol: class ReportDataLoader`

- [ ] **Step 3: 로더를 만든다**

`src/main/java/project/study/metrics/report/ReportDataLoader.java`:
```java
package project.study.metrics.report;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.dday.service.DdayMetricsService;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.amplitude.AmplitudeQueryService;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.metrics.report.fact.UserFact;
import project.study.room.service.RoomMetricsService;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.subject.service.SubjectMetricsService;
import project.study.user.service.UserService;

/**
 * 도메인 서비스에서 리포트 기간의 사실을 모아 유저별 타임라인으로 묶는다. metrics는 다른 도메인 테이블을 직접 읽지 않는다(스펙 §8).
 *
 * <p>원본 사실은 {@code [A−61, A]}, 가입자는 {@code [A−68, A]}(28~34일 뒤 리텐션의 지난주 코호트)다(스펙 §3).
 */
@Component
public class ReportDataLoader {

    static final int FACT_DAYS_BACK = 61;
    static final int SIGNUP_DAYS_BACK = 68;

    private final UserService userService;
    private final StudySessionMetricsService sessionMetricsService;
    private final RoomMetricsService roomMetricsService;
    private final SubjectMetricsService subjectMetricsService;
    private final DdayMetricsService ddayMetricsService;
    private final AmplitudeQueryService amplitudeQueryService;
    private final Set<Long> excludedUserIds;

    public ReportDataLoader(
            UserService userService,
            StudySessionMetricsService sessionMetricsService,
            RoomMetricsService roomMetricsService,
            SubjectMetricsService subjectMetricsService,
            DdayMetricsService ddayMetricsService,
            AmplitudeQueryService amplitudeQueryService,
            @Value("${metrics.report.excluded-user-ids:}") Set<Long> excludedUserIds) {
        this.userService = userService;
        this.sessionMetricsService = sessionMetricsService;
        this.roomMetricsService = roomMetricsService;
        this.subjectMetricsService = subjectMetricsService;
        this.ddayMetricsService = ddayMetricsService;
        this.amplitudeQueryService = amplitudeQueryService;
        this.excludedUserIds = Set.copyOf(excludedUserIds);
    }

    public ReportData load(LocalDate anchor) {
        LocalDate factFrom = anchor.minusDays(FACT_DAYS_BACK);
        // 어제 늦게 가입한 사람의 "가입 3시간 안 공부"가 오늘(A+1) 새벽으로 넘어가서 세션만 하루 더 읽는다
        List<SessionFact> sessions =
                kept(sessionMetricsService.findSessionFacts(factFrom, anchor.plusDays(1)), SessionFact::userId);
        Set<Long> activeIds = sessions.stream().map(SessionFact::userId).collect(toSet());
        List<UserFact> users = kept(
                userService.findReportUsers(Kst.startOf(anchor.minusDays(SIGNUP_DAYS_BACK)), activeIds),
                UserFact::userId);
        Set<Long> userIds = users.stream().map(UserFact::userId).collect(toSet());

        Map<Long, Map<Activity, List<Instant>>> activities =
                activities(Kst.startOf(factFrom), Kst.startOf(anchor.plusDays(1)), userIds);
        Map<Long, List<SubjectFact>> subjects = subjectMetricsService.findSubjectsOf(userIds).stream()
                .collect(groupingBy(SubjectFact::userId));
        List<AmplitudeDailyCount> counts = amplitudeQueryService.findCounts(factFrom, anchor, AmplitudeKeys.ALL);
        Map<Long, List<AmplitudeDailyCount>> countsByUser =
                counts.stream().collect(groupingBy(AmplitudeDailyCount::userId));
        Map<Long, AmplitudeAttribute> attributes = amplitudeQueryService.findAttributes(userIds);
        Map<Long, List<SessionFact>> sessionsByUser = sessions.stream().collect(groupingBy(SessionFact::userId));

        List<UserTimeline> timelines = users.stream()
                .map(user -> new UserTimeline(
                        user,
                        sessionsByUser.getOrDefault(user.userId(), List.of()),
                        activities.getOrDefault(user.userId(), Map.of()),
                        subjects.getOrDefault(user.userId(), List.of()),
                        countsByUser.getOrDefault(user.userId(), List.of()),
                        attributes.get(user.userId())))
                .toList();
        return new ReportData(
                anchor, timelines, amplitudeQueryService.collectedDates(factFrom, anchor), appOpened(counts, anchor));
    }

    private Map<Long, Map<Activity, List<Instant>>> activities(Instant from, Instant to, Set<Long> userIds) {
        Map<Long, Map<Activity, List<Instant>>> byUser = new HashMap<>();
        add(byUser, Activity.ROOM_JOIN, roomMetricsService.findJoins(from, to));
        add(byUser, Activity.TASK_DONE, subjectMetricsService.findTasksDone(from, to));
        add(byUser, Activity.DDAY_SET, ddayMetricsService.findDdaysOf(userIds));
        return byUser;
    }

    private static void add(
            Map<Long, Map<Activity, List<Instant>>> byUser, Activity activity, List<UserEventFact> facts) {
        for (UserEventFact fact : facts) {
            byUser.computeIfAbsent(fact.userId(), id -> new EnumMap<>(Activity.class))
                    .computeIfAbsent(activity, key -> new ArrayList<>())
                    .add(fact.at());
        }
    }

    private Set<Long> appOpened(List<AmplitudeDailyCount> counts, LocalDate anchor) {
        return counts.stream()
                .filter(count -> count.date().equals(anchor) && count.count() > 0)
                .filter(count -> AmplitudeKeys.APP_OPEN.contains(count.key()))
                .map(AmplitudeDailyCount::userId)
                .filter(userId -> !excludedUserIds.contains(userId))
                .collect(toUnmodifiableSet());
    }

    private <T> List<T> kept(List<T> facts, Function<T, Long> userId) {
        return facts.stream()
                .filter(fact -> !excludedUserIds.contains(userId.apply(fact)))
                .toList();
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.ReportDataLoaderTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/metrics/report/ReportDataLoader.java src/test/java/project/study/metrics/report/ReportDataLoaderTest.java
git commit -m "feat: 리포트 데이터를 도메인 서비스에서 모은다 (BY-904)"
```

---

### Task 4: 규칙 카탈로그와 상관 계산

**Files:**
- Create: `src/main/java/project/study/metrics/report/Cohorts.java`, `FirstWeekHabits.java`
- Create: `src/main/java/project/study/metrics/report/rule/RuleGroup.java`, `RuleSource.java`, `SegmentRule.java`, `CohortStats.java`,
  `TwoByTwo.java`, `Exposure.java`, `Strength.java`, `StudyRules.java`, `HabitRules.java`, `ActionRules.java`, `RuleCatalog.java`
- Test: `src/test/java/project/study/metrics/report/FirstWeekHabitsTest.java`,
  `src/test/java/project/study/metrics/report/rule/TwoByTwoTest.java`, `CohortStatsTest.java`, `RuleCatalogTest.java`

**Interfaces:**
- Consumes: Task 2 `UserTimeline`, `ReportData`, `Rate`, `AmplitudeKeys`, `Kst`, 픽스처
- Produces:
  - `Cohorts.weekTwoFrom(LocalDate)`/`weekTwoTo(LocalDate)`(= A−40 / A−13), `weekTwo(ReportData, LocalDate): List<UserTimeline>`,
    `firstWeeksCollected(ReportData, LocalDate): boolean`([A−40, A−7] 수집), `firstWeekStudyDays(UserTimeline): int`,
    `fixedSegment(UserTimeline): boolean`(첫 주 3일+), `retainedWeekTwo(UserTimeline): boolean`(D7~D13 1일+)
  - `FirstWeekHabits.sessions/pausesPerSession/averageSessionFocusSec/distractionsPerFocusHour(UserTimeline): OptionalDouble`(sessions는 List),
    `focusShareStartedIn(UserTimeline, Set<Integer>): double`, `topStartBand(UserTimeline): OptionalInt`, `socialFocusPercent(UserTimeline): OptionalInt`,
    `NIGHT_HOURS`, `MORNING_HOURS`
  - `record SegmentRule(String key, RuleGroup group, RuleSource source, Period period, String phrase, Matcher matcher)` — `name()`, `matches(UserTimeline, CohortStats)`, `amplitude()`
  - `record CohortStats(double medianPausesPerSession, double medianDistractionsPerFocusHour)` — `of(List<UserTimeline>)`
  - `record TwoByTwo(int a, int b, int c, int d)` — `of(List<UserTimeline>, Predicate<UserTimeline> did, Predicate<UserTimeline> retained)`,
    `doers() nonDoers() total() retained()`, `doerRetention() nonDoerRetention(): Rate`, `phi(): double`(분모 0이면 NaN), `exposure(): Exposure`
  - `enum Exposure { PASS, TOO_FEW, TOO_COMMON, WEAK, NOT_COLLECTED }` + `label()`, `enum Strength { STRONG, MODERATE, WEAK, NONE }` + `of(double)`·`label()`
  - `RuleCatalog.all(): List<SegmentRule>`(40개, 묶음 A→I 순), `behaviors()`(묶음 D~I 22개), `FIXED_RULE_KEY = "study-days-3"`, `fixed(SegmentRule)`

- [ ] **Step 1: 테스트를 쓴다**

`src/test/java/project/study/metrics/report/FirstWeekHabitsTest.java`:
```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FirstWeekHabitsTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 29);

    @Test
    void 습관_값은_첫_주의_순공_1분_이상_세션만_센다() {
        UserTimeline timeline = user(1, D0)
                .session(D0, 22, 60, 1, true)
                .session(D0.plusDays(1), 22, 30, 0, false)
                .sessionAt(TimelineBuilder.at(D0, 9), D0, 30) // 30초 — 습관에서 빠진다
                .session(D0.plusDays(7), 7, 90, 5, false) // 둘째 주 — 빠진다
                .build();

        assertThat(FirstWeekHabits.sessions(timeline)).hasSize(2);
        assertThat(FirstWeekHabits.pausesPerSession(timeline).getAsDouble()).isEqualTo(0.5);
        assertThat(FirstWeekHabits.averageSessionFocusSec(timeline).getAsDouble()).isEqualTo(2700);
        assertThat(FirstWeekHabits.socialFocusPercent(timeline).getAsInt()).isEqualTo(67);
        assertThat(FirstWeekHabits.topStartBand(timeline).getAsInt()).isEqualTo(7); // 21~24시
        assertThat(FirstWeekHabits.focusShareStartedIn(timeline, FirstWeekHabits.NIGHT_HOURS)).isEqualTo(1.0);
    }

    @Test
    void 딴짓은_첫_주_순공_1시간당_횟수다() {
        UserTimeline timeline = user(1, D0).studyOnDay(0, 120).amp(AmplitudeKeys.DISTRACTED, 0, 3).build();

        assertThat(FirstWeekHabits.distractionsPerFocusHour(timeline).getAsDouble()).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void 세션이_없으면_값이_없다() {
        UserTimeline timeline = user(1, D0).build();

        assertThat(FirstWeekHabits.pausesPerSession(timeline)).isEmpty();
        assertThat(FirstWeekHabits.distractionsPerFocusHour(timeline)).isEmpty();
        assertThat(FirstWeekHabits.topStartBand(timeline)).isEmpty();
        assertThat(FirstWeekHabits.socialFocusPercent(timeline)).isEmpty();
        assertThat(FirstWeekHabits.focusShareStartedIn(timeline, FirstWeekHabits.MORNING_HOURS)).isZero();
    }
}
```

`src/test/java/project/study/metrics/report/rule/TwoByTwoTest.java`:
```java
package project.study.metrics.report.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;

class TwoByTwoTest {

    @Test
    void phi는_2x2_표의_상관이다() {
        TwoByTwo table = new TwoByTwo(8, 6, 8, 230);

        assertThat(table.phi()).isCloseTo(0.5052, within(1e-4));
        assertThat(table.doerRetention()).isEqualTo(Rate.of(8, 14));
        assertThat(table.nonDoerRetention()).isEqualTo(Rate.of(8, 238));
        assertThat(table.exposure()).isEqualTo(Exposure.PASS);
    }

    @Test
    void 한_쪽이라도_5명_미만이면_사람_부족이다() {
        assertThat(new TwoByTwo(4, 0, 1, 95).exposure()).isEqualTo(Exposure.TOO_FEW);
        assertThat(new TwoByTwo(50, 45, 0, 4).exposure()).isEqualTo(Exposure.TOO_FEW);
        assertThat(new TwoByTwo(5, 0, 3, 92).exposure()).isEqualTo(Exposure.PASS);
    }

    @Test
    void 한_사람이_90퍼센트를_넘으면_너무_흔하다() {
        assertThat(new TwoByTwo(5, 50, 0, 5).exposure()).isEqualTo(Exposure.TOO_COMMON); // 55/60
        assertThat(new TwoByTwo(20, 34, 0, 6).exposure()).isEqualTo(Exposure.PASS); // 54/60 = 90%, phi 0.24
    }

    @Test
    void 상관이_0_2_미만이거나_계산할_수_없으면_약하다() {
        assertThat(new TwoByTwo(1, 9, 1, 9).exposure()).isEqualTo(Exposure.WEAK);
        assertThat(new TwoByTwo(0, 10, 0, 10).phi()).isNaN();
        assertThat(new TwoByTwo(0, 10, 0, 10).exposure()).isEqualTo(Exposure.WEAK);
    }

    @Test
    void 강도는_Compass_구간을_따른다() {
        assertThat(Strength.of(0.4)).isEqualTo(Strength.STRONG);
        assertThat(Strength.of(0.39)).isEqualTo(Strength.MODERATE);
        assertThat(Strength.of(0.3)).isEqualTo(Strength.MODERATE);
        assertThat(Strength.of(0.2)).isEqualTo(Strength.WEAK);
        assertThat(Strength.of(0.19)).isEqualTo(Strength.NONE);
        assertThat(Strength.of(Double.NaN)).isEqualTo(Strength.NONE);
    }
}
```

`src/test/java/project/study/metrics/report/rule/CohortStatsTest.java`:
```java
package project.study.metrics.report.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CohortStatsTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);

    @Test
    void 세션당_일시정지_중앙값은_값이_있는_유저만으로_구한다() {
        CohortStats stats = CohortStats.of(List.of(
                user(1, D0).session(D0, 20, 30, 1, false).build(),
                user(2, D0).session(D0, 20, 30, 3, false).build(),
                user(3, D0).build())); // 세션 없음 — 빠진다

        assertThat(stats.medianPausesPerSession()).isEqualTo(2.0);
    }

    @Test
    void 홀수면_가운데_값이고_비어_있으면_NaN이다() {
        CohortStats odd = CohortStats.of(List.of(
                user(1, D0).session(D0, 20, 30, 1, false).build(),
                user(2, D0).session(D0, 20, 30, 5, false).build(),
                user(3, D0).session(D0, 20, 30, 2, false).build()));

        assertThat(odd.medianPausesPerSession()).isEqualTo(2.0);
        assertThat(CohortStats.of(List.of()).medianPausesPerSession()).isNaN();
    }
}
```

`src/test/java/project/study/metrics/report/rule/RuleCatalogTest.java`:
```java
package project.study.metrics.report.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;

class RuleCatalogTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 1);
    private static final CohortStats NO_STATS = new CohortStats(Double.NaN, Double.NaN);

    private static SegmentRule rule(String key) {
        return RuleCatalog.all().stream().filter(rule -> rule.key().equals(key)).findFirst().orElseThrow();
    }

    private static boolean matches(String key, UserTimeline user) {
        return rule(key).matches(user, NO_STATS);
    }

    @Test
    void 카탈로그는_묶음별로_40개다() {
        Map<RuleGroup, Long> byGroup = RuleCatalog.all().stream()
                .collect(Collectors.groupingBy(SegmentRule::group, Collectors.counting()));

        assertThat(RuleCatalog.all()).hasSize(40);
        assertThat(RuleCatalog.all().stream().map(SegmentRule::key).distinct()).hasSize(40);
        assertThat(byGroup)
                .containsEntry(RuleGroup.STREAK, 12L)
                .containsEntry(RuleGroup.STUDY_DAYS, 4L)
                .containsEntry(RuleGroup.FIRST_DAY, 2L)
                .containsEntry(RuleGroup.FEATURE, 5L)
                .containsEntry(RuleGroup.APP_EVENT, 6L)
                .containsEntry(RuleGroup.HABIT, 5L)
                .containsEntry(RuleGroup.SCREEN, 3L)
                .containsEntry(RuleGroup.INVITE, 1L)
                .containsEntry(RuleGroup.RECORD_PUSH, 2L);
        assertThat(RuleCatalog.behaviors()).hasSize(22);
    }

    @Test
    void 규칙_이름은_파라미터에서_만든다() {
        Map<String, String> names =
                RuleCatalog.all().stream().collect(Collectors.toMap(SegmentRule::key, SegmentRule::name));

        assertThat(names)
                .containsEntry("streak-2-10", "첫 주에 2일 연속 공부")
                .containsEntry("streak-3-60", "첫 주에 3일 연속 하루 순공 1시간 이상")
                .containsEntry("streak-4-120", "첫 주에 4일 연속 하루 순공 2시간 이상")
                .containsEntry("study-days-3", "첫 주에 공부한 날 3일 이상")
                .containsEntry("first-day-30", "가입한 날 순공 30분 이상")
                .containsEntry("room-3", "첫 주에 소셜 방 3번 이상 참여")
                .containsEntry("invite-opened", "가입 이틀 안에 초대 링크로 들어옴");
        assertThat(RuleCatalog.fixed(rule(RuleCatalog.FIXED_RULE_KEY))).isTrue();
        assertThat(rule("room-3").phrase()).isEqualTo("소셜 방 3번 이상 참여");
    }

    @Test
    void 연속_규칙은_하루_순공_하한을_넘긴_날이_이어져야_한다() {
        UserTimeline twoHourDays = user(1, D0).studyOnDay(1, 60).studyOnDay(2, 60).build();
        UserTimeline broken = user(2, D0).studyOnDay(1, 60).studyOnDay(2, 20).build();

        assertThat(matches("streak-2-60", twoHourDays)).isTrue();
        assertThat(matches("streak-2-60", broken)).isFalse();
        assertThat(matches("streak-2-10", broken)).isTrue();
    }

    @Test
    void 첫_주_규칙은_D0부터_D6까지만_본다() {
        UserTimeline user = user(1, D0)
                .studyOnDays(0, 3, 7)
                .activity(Activity.ROOM_JOIN, 6, 3)
                .activity(Activity.ROOM_JOIN, 7, 5)
                .build();

        assertThat(matches("study-days-2", user)).isTrue();
        assertThat(matches("study-days-3", user)).isFalse();
        assertThat(matches("room-3", user)).isTrue();
        assertThat(matches("first-day-30", user)).isTrue();
        assertThat(matches("first-day-60", user)).isFalse();
    }

    @Test
    void Amplitude_규칙은_이벤트_키로_판정한다() {
        UserTimeline user = user(1, D0)
                .amp(AmplitudeKeys.APP_LAUNCHED, 0, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 2, 4)
                .amp(AmplitudeKeys.APP_LAUNCHED, 5, 1)
                .amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1)
                .amp(AmplitudeKeys.INVITE_OPENED, 2, 1) // 이틀(D0~D1) 밖
                .build();

        assertThat(matches("app-days-3", user)).isTrue();
        assertThat(matches("simple-mode", user)).isTrue();
        assertThat(matches("landscape", user)).isFalse();
        assertThat(matches("invite-opened", user)).isFalse();
        assertThat(rule("simple-mode").amplitude()).isTrue();
        assertThat(rule("room-3").amplitude()).isFalse();
    }

    @Test
    void 중앙값_규칙은_코호트_통계와_비교하고_값이_없으면_안_한_것이다() {
        UserTimeline calm = user(1, D0).session(D0, 20, 30, 1, false).build();
        UserTimeline restless = user(2, D0).session(D0, 20, 30, 4, false).build();
        CohortStats stats = new CohortStats(2.0, Double.NaN);
        Function<UserTimeline, Boolean> fewPauses = user -> rule("few-pauses").matches(user, stats);

        assertThat(fewPauses.apply(calm)).isTrue();
        assertThat(fewPauses.apply(restless)).isFalse();
        assertThat(fewPauses.apply(user(3, D0).build())).isFalse();
        assertThat(rule("few-distractions").matches(calm, stats)).isFalse(); // 중앙값 NaN
    }

    @Test
    void 시간대_규칙은_순공_절반_이상이_그_시간대에_시작했는지_본다() {
        UserTimeline night = user(1, D0).session(D0, 23, 60, 0, false).session(D0, 15, 50, 0, false).build();
        UserTimeline morning = user(2, D0).session(D0, 6, 40, 0, false).session(D0, 13, 40, 0, false).build();

        assertThat(matches("night-study", night)).isTrue();
        assertThat(matches("morning-study", morning)).isTrue();
        assertThat(matches("morning-study", night)).isFalse();
        assertThat(matches("long-sessions", night)).isTrue();
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.FirstWeekHabitsTest" --tests "project.study.metrics.report.rule.*"`
Expected: FAIL — `cannot find symbol: class FirstWeekHabits`

- [ ] **Step 3: 코호트·습관 도구를 만든다**

`src/main/java/project/study/metrics/report/Cohorts.java`:
```java
package project.study.metrics.report;

import java.time.LocalDate;
import java.util.List;

/** 세그먼트 비교의 코호트 C와 첫 주·둘째 주 판정 (스펙 §4.3). */
public final class Cohorts {

    /** 고정 세그먼트 S1 — 첫 주(D0~D6) 공부한 날 3일 이상. */
    public static final int FIXED_SEGMENT_DAYS = 3;

    private static final int NEWEST_SIGNUP_DAYS_BACK = 13;
    private static final int OLDEST_SIGNUP_DAYS_BACK = 40;
    private static final int LAST_FIRST_WEEK_DAYS_BACK = 7;

    private Cohorts() {}

    /** 코호트 C = 둘째 주까지 끝난 최근 28일치 가입자, 가입일 {@code [A−40, A−13]}. */
    public static LocalDate weekTwoFrom(LocalDate anchor) {
        return anchor.minusDays(OLDEST_SIGNUP_DAYS_BACK);
    }

    public static LocalDate weekTwoTo(LocalDate anchor) {
        return anchor.minusDays(NEWEST_SIGNUP_DAYS_BACK);
    }

    public static List<UserTimeline> weekTwo(ReportData data, LocalDate anchor) {
        return data.signedUpBetween(weekTwoFrom(anchor), weekTwoTo(anchor));
    }

    /** 코호트 C 전원의 첫 주가 걸친 {@code [A−40, A−7]}에 Amplitude가 모두 수집됐는지. */
    public static boolean firstWeeksCollected(ReportData data, LocalDate anchor) {
        return data.amplitudeCollected(weekTwoFrom(anchor), anchor.minusDays(LAST_FIRST_WEEK_DAYS_BACK));
    }

    public static int firstWeekStudyDays(UserTimeline user) {
        return user.studyDays(user.day(0), user.day(6));
    }

    public static boolean fixedSegment(UserTimeline user) {
        return firstWeekStudyDays(user) >= FIXED_SEGMENT_DAYS;
    }

    /** 2주차 리텐션 — 둘째 주(D7~D13)에 공부한 날이 하루라도 있다. */
    public static boolean retainedWeekTwo(UserTimeline user) {
        return user.studyDays(user.day(7), user.day(13)) >= 1;
    }
}
```

`src/main/java/project/study/metrics/report/FirstWeekHabits.java`:
```java
package project.study.metrics.report;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import project.study.metrics.report.fact.SessionFact;
import project.study.studysession.StudySessionThresholds;

/**
 * 첫 주(D0~D6) 공부 습관 값 — 규칙 묶음 F와 명단 카드의 "습관" 줄이 같은 계산을 쓴다.
 *
 * <p>세션 단위 값은 앱 기록 목록과 같은 잣대로 순공 1분 이상 세션만 센다(잘못 눌렀다 끈 세션이 평균을 깎지 않게).
 */
public final class FirstWeekHabits {

    public static final Set<Integer> NIGHT_HOURS = Set.of(21, 22, 23, 0, 1, 2);
    public static final Set<Integer> MORNING_HOURS = Set.of(5, 6, 7, 8);

    private static final int BANDS = 8;
    private static final int HOURS_PER_BAND = 3;
    private static final double SECONDS_PER_HOUR = 3600.0;

    private FirstWeekHabits() {}

    public static List<SessionFact> sessions(UserTimeline user) {
        return user.sessions(user.day(0), user.day(6)).stream()
                .filter(session -> session.focusSec() >= StudySessionThresholds.MIN_LIST_FOCUS_SEC)
                .toList();
    }

    public static OptionalDouble pausesPerSession(UserTimeline user) {
        return sessions(user).stream().mapToInt(SessionFact::pauseCount).average();
    }

    public static OptionalDouble averageSessionFocusSec(UserTimeline user) {
        return sessions(user).stream().mapToInt(SessionFact::focusSec).average();
    }

    /** 순공 1시간당 딴짓 이벤트 수(Amplitude). 첫 주 순공이 없으면 값이 없다. */
    public static OptionalDouble distractionsPerFocusHour(UserTimeline user) {
        int focusSec = user.focusSec(user.day(0), user.day(6));
        if (focusSec <= 0) {
            return OptionalDouble.empty();
        }
        int distractions = user.amplitudeCount(AmplitudeKeys.DISTRACTED, user.day(0), user.day(6));
        return OptionalDouble.of(distractions * SECONDS_PER_HOUR / focusSec);
    }

    /** 순공 중 KST 시작 시각이 {@code hours}에 드는 몫(0~1). 순공이 없으면 0. */
    public static double focusShareStartedIn(UserTimeline user, Set<Integer> hours) {
        List<SessionFact> sessions = sessions(user);
        int total = sessions.stream().mapToInt(SessionFact::focusSec).sum();
        if (total == 0) {
            return 0;
        }
        int inHours = sessions.stream()
                .filter(session -> hours.contains(Kst.hour(session.startedAt())))
                .mapToInt(SessionFact::focusSec)
                .sum();
        return (double) inHours / total;
    }

    /** 순공이 가장 많은 3시간 구간(0 = 00~03시 … 7 = 21~24시). 같으면 이른 구간. */
    public static OptionalInt topStartBand(UserTimeline user) {
        int[] focus = new int[BANDS];
        sessions(user).forEach(session -> focus[Kst.hour(session.startedAt()) / HOURS_PER_BAND] += session.focusSec());
        int best = -1;
        for (int band = 0; band < BANDS; band++) {
            if (focus[band] > 0 && (best < 0 || focus[band] > focus[best])) {
                best = band;
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /** 소셜 세션 순공 비율(%). 순공이 없으면 값이 없다. */
    public static OptionalInt socialFocusPercent(UserTimeline user) {
        List<SessionFact> sessions = sessions(user);
        int total = sessions.stream().mapToInt(SessionFact::focusSec).sum();
        if (total == 0) {
            return OptionalInt.empty();
        }
        int social = sessions.stream()
                .filter(SessionFact::social)
                .mapToInt(SessionFact::focusSec)
                .sum();
        return OptionalInt.of((int) Math.round(100.0 * social / total));
    }
}
```

- [ ] **Step 4: 규칙 타입과 상관 계산을 만든다**

`src/main/java/project/study/metrics/report/rule/RuleGroup.java`:
```java
package project.study.metrics.report.rule;

/** 규칙 카탈로그 묶음 A~I (스펙 §5). 선언 순서가 카탈로그 순서다. */
public enum RuleGroup {
    STREAK,
    STUDY_DAYS,
    FIRST_DAY,
    FEATURE,
    APP_EVENT,
    HABIT,
    SCREEN,
    INVITE,
    RECORD_PUSH;

    /** 묶음 D~I — 공부량이 아니라 행동이라 "주로 한 행동" 후보다. */
    public boolean behavior() {
        return compareTo(FEATURE) >= 0;
    }
}
```

`src/main/java/project/study/metrics/report/rule/RuleSource.java`:
```java
package project.study.metrics.report.rule;

/** 규칙이 읽는 데이터 — Amplitude 규칙은 판정 기간이 미수집이면 그날 평가에서 빠진다. */
public enum RuleSource {
    SERVER,
    AMPLITUDE
}
```

`src/main/java/project/study/metrics/report/rule/SegmentRule.java`:
```java
package project.study.metrics.report.rule;

import project.study.metrics.report.UserTimeline;

/**
 * 행동 규칙 하나. 이름은 기간 접두어 + 문구로 만든다 — 메시지 설명과 판정이 어긋나지 않게 같은 파라미터에서 나온다(스펙 §5).
 *
 * @param phrase "주로 한 행동" 목록에 쓰는 짧은 이름
 */
public record SegmentRule(
        String key, RuleGroup group, RuleSource source, Period period, String phrase, Matcher matcher) {

    public enum Period {
        FIRST_WEEK("첫 주에 "),
        SIGNUP_DAY("가입한 날 "),
        FIRST_TWO_DAYS("가입 이틀 안에 ");

        private final String prefix;

        Period(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    @FunctionalInterface
    public interface Matcher {
        boolean test(UserTimeline user, CohortStats stats);
    }

    public String name() {
        return period.prefix() + phrase;
    }

    public boolean matches(UserTimeline user, CohortStats stats) {
        return matcher.test(user, stats);
    }

    public boolean amplitude() {
        return source == RuleSource.AMPLITUDE;
    }
}
```

`src/main/java/project/study/metrics/report/rule/CohortStats.java`:
```java
package project.study.metrics.report.rule;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;
import project.study.metrics.report.FirstWeekHabits;
import project.study.metrics.report.UserTimeline;

/** 중앙값 규칙(묶음 F 앞 두 개)이 비교할 코호트 중앙값. 값을 계산할 수 있는 유저만으로 구하고, 아무도 없으면 NaN. */
public record CohortStats(double medianPausesPerSession, double medianDistractionsPerFocusHour) {

    public static CohortStats of(List<UserTimeline> cohort) {
        return new CohortStats(
                median(cohort, FirstWeekHabits::pausesPerSession),
                median(cohort, FirstWeekHabits::distractionsPerFocusHour));
    }

    static double median(List<UserTimeline> cohort, Function<UserTimeline, OptionalDouble> metric) {
        double[] values = cohort.stream()
                .map(metric)
                .filter(OptionalDouble::isPresent)
                .mapToDouble(OptionalDouble::getAsDouble)
                .sorted()
                .toArray();
        if (values.length == 0) {
            return Double.NaN;
        }
        int middle = values.length / 2;
        return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
    }
}
```

`src/main/java/project/study/metrics/report/rule/Exposure.java`:
```java
package project.study.metrics.report.rule;

/** 노출 조건 판정 (스펙 §6). 라벨은 탐색 스레드 꼬리의 "제외" 이유다. */
public enum Exposure {
    PASS("통과"),
    TOO_FEW("사람 부족"),
    TOO_COMMON("너무 흔함"),
    WEAK("상관 약함"),
    NOT_COLLECTED("Amplitude 미수집");

    private final String label;

    Exposure(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
```

`src/main/java/project/study/metrics/report/rule/Strength.java`:
```java
package project.study.metrics.report.rule;

/** 상관 강도 — Amplitude Compass 구간(0.4 강함 / 0.3 보통 / 0.2 약함). */
public enum Strength {
    STRONG("강함"),
    MODERATE("보통"),
    WEAK("약함"),
    NONE("거의 없음");

    private final String label;

    Strength(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Strength of(double phi) {
        if (Double.isNaN(phi) || phi < 0.2) {
            return NONE;
        }
        if (phi >= 0.4) {
            return STRONG;
        }
        return phi >= 0.3 ? MODERATE : WEAK;
    }
}
```

`src/main/java/project/study/metrics/report/rule/TwoByTwo.java`:
```java
package project.study.metrics.report.rule;

import java.util.List;
import java.util.function.Predicate;
import project.study.metrics.report.Rate;
import project.study.metrics.report.UserTimeline;

/**
 * 규칙 하나의 2×2 표: a = 함·남음, b = 함·안 남음, c = 안 함·남음, d = 안 함·안 남음 (스펙 §6).
 */
public record TwoByTwo(int a, int b, int c, int d) {

    public static final int MIN_SIDE = 5;
    public static final int MAX_DOER_PERCENT = 90;
    public static final double MIN_PHI = 0.2;

    public static TwoByTwo of(List<UserTimeline> cohort, Predicate<UserTimeline> did, Predicate<UserTimeline> retained) {
        int[] cells = new int[4];
        for (UserTimeline user : cohort) {
            int row = did.test(user) ? 0 : 2;
            int column = retained.test(user) ? 0 : 1;
            cells[row + column]++;
        }
        return new TwoByTwo(cells[0], cells[1], cells[2], cells[3]);
    }

    public int doers() {
        return a + b;
    }

    public int nonDoers() {
        return c + d;
    }

    public int total() {
        return a + b + c + d;
    }

    public int retained() {
        return a + c;
    }

    public Rate doerRetention() {
        return Rate.of(a, doers());
    }

    public Rate nonDoerRetention() {
        return Rate.of(c, nonDoers());
    }

    /** phi = (ad − bc) / √((a+b)(c+d)(a+c)(b+d)). 분모가 0이면 NaN. */
    public double phi() {
        double denominator = Math.sqrt((double) doers() * nonDoers() * retained() * (b + d));
        return denominator == 0 ? Double.NaN : ((double) a * d - (double) b * c) / denominator;
    }

    public Exposure exposure() {
        if (doers() < MIN_SIDE || nonDoers() < MIN_SIDE) {
            return Exposure.TOO_FEW;
        }
        if (doers() * 100L > (long) total() * MAX_DOER_PERCENT) {
            return Exposure.TOO_COMMON;
        }
        double phi = phi();
        return Double.isNaN(phi) || phi < MIN_PHI ? Exposure.WEAK : Exposure.PASS;
    }
}
```

- [ ] **Step 5: 규칙 40개를 만든다**

`src/main/java/project/study/metrics/report/rule/StudyRules.java`:
```java
package project.study.metrics.report.rule;

import static project.study.metrics.report.rule.RuleGroup.FIRST_DAY;
import static project.study.metrics.report.rule.RuleGroup.STREAK;
import static project.study.metrics.report.rule.RuleGroup.STUDY_DAYS;
import static project.study.metrics.report.rule.RuleSource.SERVER;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_WEEK;
import static project.study.metrics.report.rule.SegmentRule.Period.SIGNUP_DAY;

import java.util.ArrayList;
import java.util.List;

/** 묶음 A(연속 공부) · B(공부한 날 수) · C(첫날) — 서버 세션으로 판정한다. */
final class StudyRules {

    private static final int[] RUN_DAYS = {2, 3, 4};
    private static final int[] RUN_MINUTES = {10, 30, 60, 120};
    private static final int[] STUDY_DAYS_AT_LEAST = {2, 3, 4, 5};
    private static final int[] FIRST_DAY_MINUTES = {30, 60};
    private static final int STUDY_DAY_MINUTES = 10;

    private StudyRules() {}

    static List<SegmentRule> streaks() {
        List<SegmentRule> rules = new ArrayList<>();
        for (int days : RUN_DAYS) {
            for (int minutes : RUN_MINUTES) {
                rules.add(streak(days, minutes));
            }
        }
        return rules;
    }

    // 하루 순공 10분은 "공부한 날" 정의와 같아 "N일 연속 공부"로 줄여 쓴다
    private static SegmentRule streak(int days, int minutes) {
        String phrase = minutes == STUDY_DAY_MINUTES
                ? "%d일 연속 공부".formatted(days)
                : "%d일 연속 하루 순공 %s 이상".formatted(days, minutesLabel(minutes));
        return new SegmentRule(
                "streak-%d-%d".formatted(days, minutes),
                STREAK,
                SERVER,
                FIRST_WEEK,
                phrase,
                (user, stats) -> user.longestRun(user.day(0), user.day(6), minutes * 60) >= days);
    }

    static List<SegmentRule> studyDays() {
        List<SegmentRule> rules = new ArrayList<>();
        for (int atLeast : STUDY_DAYS_AT_LEAST) {
            rules.add(new SegmentRule(
                    "study-days-" + atLeast,
                    STUDY_DAYS,
                    SERVER,
                    FIRST_WEEK,
                    "공부한 날 %d일 이상".formatted(atLeast),
                    (user, stats) -> user.studyDays(user.day(0), user.day(6)) >= atLeast));
        }
        return rules;
    }

    static List<SegmentRule> firstDay() {
        List<SegmentRule> rules = new ArrayList<>();
        for (int minutes : FIRST_DAY_MINUTES) {
            rules.add(new SegmentRule(
                    "first-day-" + minutes,
                    FIRST_DAY,
                    SERVER,
                    SIGNUP_DAY,
                    "순공 %s 이상".formatted(minutesLabel(minutes)),
                    (user, stats) -> user.focusSec(user.day(0)) >= minutes * 60));
        }
        return rules;
    }

    static String minutesLabel(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + "시간" : minutes + "분";
    }
}
```

`src/main/java/project/study/metrics/report/rule/HabitRules.java`:
```java
package project.study.metrics.report.rule;

import static project.study.metrics.report.rule.RuleGroup.HABIT;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_WEEK;

import java.util.List;
import java.util.OptionalDouble;
import project.study.metrics.report.FirstWeekHabits;

/** 묶음 F(공부 습관). 중앙값 규칙은 값을 계산할 수 없는 유저(세션·순공 없음)를 "안 함"으로 본다. */
final class HabitRules {

    private static final int LONG_SESSION_SEC = 30 * 60;
    private static final double MAJORITY = 0.5;

    private HabitRules() {}

    static List<SegmentRule> all() {
        return List.of(
                new SegmentRule(
                        "few-pauses",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "세션당 일시정지가 적음(중앙값 이하)",
                        (user, stats) ->
                                atMost(FirstWeekHabits.pausesPerSession(user), stats.medianPausesPerSession())),
                new SegmentRule(
                        "few-distractions",
                        HABIT,
                        RuleSource.AMPLITUDE,
                        FIRST_WEEK,
                        "딴짓이 적음(순공 1시간당, 중앙값 이하)",
                        (user, stats) -> atMost(
                                FirstWeekHabits.distractionsPerFocusHour(user),
                                stats.medianDistractionsPerFocusHour())),
                new SegmentRule(
                        "long-sessions",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "세션 평균 순공 30분 이상",
                        (user, stats) ->
                                FirstWeekHabits.averageSessionFocusSec(user).orElse(0) >= LONG_SESSION_SEC),
                new SegmentRule(
                        "night-study",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "순공 절반 이상을 밤(21~03시)에 시작",
                        (user, stats) ->
                                FirstWeekHabits.focusShareStartedIn(user, FirstWeekHabits.NIGHT_HOURS) >= MAJORITY),
                new SegmentRule(
                        "morning-study",
                        HABIT,
                        RuleSource.SERVER,
                        FIRST_WEEK,
                        "순공 절반 이상을 아침(05~09시)에 시작",
                        (user, stats) -> FirstWeekHabits.focusShareStartedIn(user, FirstWeekHabits.MORNING_HOURS)
                                >= MAJORITY));
    }

    // 중앙값이 NaN이면 비교가 false라 "안 함"이 된다
    private static boolean atMost(OptionalDouble value, double median) {
        return value.isPresent() && value.getAsDouble() <= median;
    }
}
```

`src/main/java/project/study/metrics/report/rule/ActionRules.java`:
```java
package project.study.metrics.report.rule;

import static project.study.metrics.report.rule.RuleSource.AMPLITUDE;
import static project.study.metrics.report.rule.RuleSource.SERVER;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_TWO_DAYS;
import static project.study.metrics.report.rule.SegmentRule.Period.FIRST_WEEK;

import java.util.List;
import java.util.function.Predicate;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;

/** 묶음 D(서버 기능) · E(앱 행동) · G(화면 설정) · H(초대 유입) · I(기록·알림). */
final class ActionRules {

    private ActionRules() {}

    static List<SegmentRule> features() {
        return List.of(
                server("room-1", "소셜 방 1번 이상 참여", user -> firstWeek(user, Activity.ROOM_JOIN) >= 1),
                server("room-3", "소셜 방 3번 이상 참여", user -> firstWeek(user, Activity.ROOM_JOIN) >= 3),
                server("subjects-3", "과목 3개 이상 등록", user -> firstWeek(user, Activity.SUBJECT_CREATED) >= 3),
                server("tasks-3", "할 일 3개 이상 완료", user -> firstWeek(user, Activity.TASK_DONE) >= 3),
                server("dday", "D-day 설정", user -> firstWeek(user, Activity.DDAY_SET) >= 1));
    }

    static List<SegmentRule> appEvents() {
        return List.of(
                new SegmentRule(
                        "app-days-3",
                        RuleGroup.APP_EVENT,
                        AMPLITUDE,
                        FIRST_WEEK,
                        "앱 실행한 날 3일 이상",
                        (user, stats) ->
                                user.amplitudeDays(AmplitudeKeys.APP_LAUNCHED, user.day(0), user.day(6)) >= 3),
                event("ambient", RuleGroup.APP_EVENT, AmplitudeKeys.AMBIENT_SOUND, "백색소음 바꿈"),
                event("planner", RuleGroup.APP_EVENT, AmplitudeKeys.PLANNER, "플래너 열어 봄"),
                event("invite-shared", RuleGroup.APP_EVENT, AmplitudeKeys.INVITE_SHARED, "초대 링크 공유"),
                event("guide", RuleGroup.APP_EVENT, AmplitudeKeys.GUIDE_FINISHED, "가이드 끝까지 봄"),
                event("ranking", RuleGroup.APP_EVENT, AmplitudeKeys.RANKING, "랭킹 봄"));
    }

    static List<SegmentRule> screen() {
        return List.of(
                event("simple-mode", RuleGroup.SCREEN, AmplitudeKeys.SIMPLE_MODE_ON, "간단 모드 켬"),
                event("landscape", RuleGroup.SCREEN, AmplitudeKeys.LANDSCAPE, "가로 화면 씀"),
                event("camera", RuleGroup.SCREEN, AmplitudeKeys.CAMERA_ON, "카메라 켬"));
    }

    static List<SegmentRule> invite() {
        return List.of(new SegmentRule(
                "invite-opened",
                RuleGroup.INVITE,
                AMPLITUDE,
                FIRST_TWO_DAYS,
                "초대 링크로 들어옴",
                (user, stats) -> user.amplitudeCount(AmplitudeKeys.INVITE_OPENED, user.day(0), user.day(1)) >= 1));
    }

    static List<SegmentRule> recordPush() {
        return List.of(
                event("records", RuleGroup.RECORD_PUSH, AmplitudeKeys.RECORDS_DATE, "기록 날짜 넘겨 봄"),
                event("push", RuleGroup.RECORD_PUSH, AmplitudeKeys.PUSH_OPENED, "푸시 알림 열어 봄"));
    }

    private static int firstWeek(UserTimeline user, Activity activity) {
        return user.activityCount(activity, user.day(0), user.day(6));
    }

    private static SegmentRule server(String key, String phrase, Predicate<UserTimeline> test) {
        return new SegmentRule(key, RuleGroup.FEATURE, SERVER, FIRST_WEEK, phrase, (user, stats) -> test.test(user));
    }

    private static SegmentRule event(String key, RuleGroup group, String eventKey, String phrase) {
        return new SegmentRule(
                key,
                group,
                AMPLITUDE,
                FIRST_WEEK,
                phrase,
                (user, stats) -> user.amplitudeCount(eventKey, user.day(0), user.day(6)) >= 1);
    }
}
```

`src/main/java/project/study/metrics/report/rule/RuleCatalog.java`:
```java
package project.study.metrics.report.rule;

import java.util.ArrayList;
import java.util.List;

/** 자동 탐색 규칙 40개 (스펙 §5). 순서 = 묶음 A→I, 묶음 안은 파라미터 순 — 순위 동률의 마지막 기준이다. */
public final class RuleCatalog {

    /** 고정 세그먼트 S1과 같은 규칙. 탐색 후보로 남기고 Top 3에 들면 "(고정 세그먼트)"를 붙인다. */
    public static final String FIXED_RULE_KEY = "study-days-3";

    private static final List<SegmentRule> ALL = build();

    private RuleCatalog() {}

    public static List<SegmentRule> all() {
        return ALL;
    }

    /** 묶음 D~I — 고정 세그먼트의 "주로 한 행동" 후보. */
    public static List<SegmentRule> behaviors() {
        return ALL.stream().filter(rule -> rule.group().behavior()).toList();
    }

    public static boolean fixed(SegmentRule rule) {
        return FIXED_RULE_KEY.equals(rule.key());
    }

    private static List<SegmentRule> build() {
        List<SegmentRule> rules = new ArrayList<>();
        rules.addAll(StudyRules.streaks());
        rules.addAll(StudyRules.studyDays());
        rules.addAll(StudyRules.firstDay());
        rules.addAll(ActionRules.features());
        rules.addAll(ActionRules.appEvents());
        rules.addAll(HabitRules.all());
        rules.addAll(ActionRules.screen());
        rules.addAll(ActionRules.invite());
        rules.addAll(ActionRules.recordPush());
        return List.copyOf(rules);
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.FirstWeekHabitsTest" --tests "project.study.metrics.report.rule.*"`
Expected: PASS (17 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/project/study/metrics/report src/test/java/project/study/metrics/report
git commit -m "feat: 행동 규칙 40개와 상관 계산을 추가한다 (BY-904)"
```

---
### Task 5: 가입·리텐션 분석

**Files:**
- Create: `src/main/java/project/study/metrics/report/Categories.java`, `OsLabels.java`
- Create: `src/main/java/project/study/metrics/report/analyzer/SignupSection.java`, `SignupAnalyzer.java`, `RetentionSection.java`, `RetentionAnalyzer.java`
- Test: `src/test/java/project/study/metrics/report/analyzer/SignupAnalyzerTest.java`, `RetentionAnalyzerTest.java`

**Interfaces:**
- Consumes: Task 2 `ReportData`·`UserTimeline`·`Rate`, Task 4 `Cohorts.firstWeekStudyDays`
- Produces:
  - `Categories.NONE = "미입력"`, `Categories.label(String code): String`, `Categories.order(String label): int`
  - `OsLabels.label(String osName): String`(iOS/Android/원문, 없으면 null)
  - `record SignupSection(LocalDate anchor, int yesterdayCount, Long minUserId, Long maxUserId, Rate yesterdayQuick, int weekTotal,
    List<HourBand> bands, List<Share> categories, Attribution attribution)` — `weekFrom()`, `peak(): Optional<HourBand>`, `bestQuick(): Optional<HourBand>`;
    중첩 `record HourBand(int startHour, int signups, int quick)`(`quickRate()`), `record Share(String label, int count)`,
    `record Attribution(int total, int adClicks, List<Share> topUtm, int missing)`
  - `SignupAnalyzer.analyze(ReportData): SignupSection`, `SignupAnalyzer.quickStudy(UserTimeline): boolean`
  - `record RetentionSection(List<RetentionRow> rows, Rate firstWeekNone, LocalDate noneFrom, LocalDate noneTo)`, 중첩
    `record RetentionRow(Window window, Rate current, Rate lastWeek, LocalDate cohortFrom, LocalDate cohortTo)`
  - `RetentionAnalyzer.Window { NEXT_DAY(1,1), WEEK_TWO(7,13), WEEK_FIVE(28,34) }`(`fromDay()`·`toDay()`), `RetentionAnalyzer.analyze(ReportData): RetentionSection`

- [ ] **Step 1: 테스트를 쓴다**

`src/test/java/project/study/metrics/report/analyzer/SignupAnalyzerTest.java`:
```java
package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.at;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.report.analyzer.SignupSection.Share;

class SignupAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    @Test
    void 어제_가입_수_회원번호_범위와_3시간_안_공부를_센다() {
        UserTimeline quick = user(503, A).signupHour(21).session(A, 22, 30, 0, false).build();
        // 23시 가입 → 다음 날 00:30 시작 세션도 3시간 안이다
        UserTimeline lateNight = user(510, A)
                .signupHour(23)
                .sessionAt(at(A.plusDays(1), 0).plusSeconds(1800), A.plusDays(1), 900)
                .build();
        UserTimeline idle = user(520, A).signupHour(10).build();
        UserTimeline older = user(400, A.minusDays(7)).build();

        SignupSection section =
                SignupAnalyzer.analyze(ReportDataFixture.data(A, true, quick, lateNight, idle, older));

        assertThat(section.yesterdayCount()).isEqualTo(3);
        assertThat(section.minUserId()).isEqualTo(503L);
        assertThat(section.maxUserId()).isEqualTo(520L);
        assertThat(section.yesterdayQuick()).isEqualTo(Rate.of(2, 3));
        assertThat(section.weekTotal()).isEqualTo(3);
        assertThat(section.weekFrom()).isEqualTo(A.minusDays(6));
    }

    @Test
    void 시간대별_가입과_바로_공부_비율이_가장_높은_구간을_고른다() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // 06~09시 5명 중 2명 바로 공부
            var builder = user(100 + i, A.minusDays(2)).signupHour(7);
            users.add(i < 2 ? builder.session(A.minusDays(2), 8, 30, 0, false).build() : builder.build());
        }
        for (int i = 0; i < 8; i++) { // 21~24시 8명 중 1명 — 가입은 가장 많다
            var builder = user(200 + i, A.minusDays(1)).signupHour(22);
            users.add(i < 1 ? builder.session(A.minusDays(1), 23, 30, 0, false).build() : builder.build());
        }
        // 00~03시 1명이 바로 공부(100%)해도 5명 미만이라 비율 후보가 아니다
        users.add(user(300, A.minusDays(3)).signupHour(1).session(A.minusDays(3), 2, 30, 0, false).build());

        SignupSection section =
                SignupAnalyzer.analyze(ReportDataFixture.data(A, true, users.toArray(UserTimeline[]::new)));

        assertThat(section.bands()).hasSize(8);
        assertThat(section.bands().get(2)).isEqualTo(new HourBand(6, 5, 2));
        assertThat(section.bands().get(7)).isEqualTo(new HourBand(21, 8, 1));
        assertThat(section.peak()).contains(new HourBand(21, 8, 1));
        assertThat(section.bestQuick()).contains(new HourBand(6, 5, 2));
    }

    @Test
    void 공부_분야와_유입을_미입력_미수집과_함께_센다() {
        UserTimeline csat1 = user(1, A).profile("a", "CSAT", null).attribute("instagram", true, "ios").build();
        UserTimeline csat2 = user(2, A).profile("b", "CSAT", null).attribute("instagram", false, "android").build();
        UserTimeline civil = user(3, A).profile("c", "CIVIL_SERVICE", null).attribute("tiktok", true, null).build();
        UserTimeline none = user(4, A).profile("d", null, "합격").build();

        SignupSection section = SignupAnalyzer.analyze(ReportDataFixture.data(A, true, csat1, csat2, civil, none));

        assertThat(section.categories())
                .containsExactly(new Share("수능", 2), new Share("공무원", 1), new Share("미입력", 1));
        assertThat(section.attribution())
                .isEqualTo(new Attribution(4, 2, List.of(new Share("instagram", 2), new Share("tiktok", 1)), 1));
    }

    @Test
    void 최근_7일_가입이_없으면_구간도_고르지_않는다() {
        SignupSection section = SignupAnalyzer.analyze(ReportDataFixture.data(A, false));

        assertThat(section.yesterdayCount()).isZero();
        assertThat(section.minUserId()).isNull();
        assertThat(section.yesterdayQuick().defined()).isFalse();
        assertThat(section.peak()).isEmpty();
        assertThat(section.bestQuick()).isEmpty();
        assertThat(section.categories()).isEmpty();
    }
}
```

`src/test/java/project/study/metrics/report/analyzer/RetentionAnalyzerTest.java`:
```java
package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;

class RetentionAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    private static RetentionRow row(RetentionSection section, Window window) {
        return section.rows().stream().filter(row -> row.window() == window).findFirst().orElseThrow();
    }

    @Test
    void 구간이_다_끝난_최근_28일치_가입자만_분모로_센다() {
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(1)).studyOnDays(1).build(), // D1 = A
                user(2, A.minusDays(28)).build(),
                user(3, A).studyOnDays(1).build(), // D1이 아직 안 왔다 — 분모에서 빠진다
                user(4, A.minusDays(29)).studyOnDays(1).build())); // 28일 밖

        RetentionRow nextDay = row(section, Window.NEXT_DAY);
        assertThat(nextDay.current()).isEqualTo(Rate.of(1, 2));
        assertThat(nextDay.cohortFrom()).isEqualTo(A.minusDays(28));
        assertThat(nextDay.cohortTo()).isEqualTo(A.minusDays(1));
    }

    @Test
    void 이주차와_오주차는_7일_구간_중_하루라도_공부하면_센다() {
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(13)).studyOnDays(10).build(),
                user(2, A.minusDays(20)).studyOnDays(6, 14).build(), // 6일 뒤·14일 뒤에만 — 안 센다
                user(3, A.minusDays(40)).studyOnDays(7).build(),
                user(4, A.minusDays(34)).studyOnDays(30).build(),
                user(5, A.minusDays(61)).studyOnDays(27, 35).build()));

        // 가입 A−34인 4번도 [A−40, A−13] 안이라 7~13일 뒤 분모에 든다
        assertThat(row(section, Window.WEEK_TWO).current()).isEqualTo(Rate.of(2, 4));
        assertThat(row(section, Window.WEEK_TWO).cohortFrom()).isEqualTo(A.minusDays(40));
        assertThat(row(section, Window.WEEK_FIVE).current()).isEqualTo(Rate.of(1, 2));
        assertThat(row(section, Window.WEEK_FIVE).cohortFrom()).isEqualTo(A.minusDays(61));
    }

    @Test
    void 지난주_값은_기준일을_7일_당겨_같은_계산을_한다() {
        // A−7 기준 다음 날 코호트 = 가입 [A−35, A−8]
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(8)).studyOnDays(1).build(),
                user(2, A.minusDays(30)).build()));

        assertThat(row(section, Window.NEXT_DAY).lastWeek()).isEqualTo(Rate.of(1, 2));
    }

    @Test
    void 첫_주에_한_번도_공부_안_한_비율을_센다() {
        RetentionSection section = RetentionAnalyzer.analyze(ReportDataFixture.data(
                A,
                false,
                user(1, A.minusDays(6)).build(),
                user(2, A.minusDays(10)).studyOnDays(6).build(),
                user(3, A.minusDays(33)).studyOnDays(7).build(), // 둘째 주에만 — 첫 주 미사용
                user(4, A.minusDays(5)).build())); // 첫 주가 안 끝났다

        assertThat(section.firstWeekNone()).isEqualTo(Rate.of(2, 3));
        assertThat(section.noneFrom()).isEqualTo(A.minusDays(33));
        assertThat(section.noneTo()).isEqualTo(A.minusDays(6));
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.analyzer.*"`
Expected: FAIL — `cannot find symbol: class SignupAnalyzer`

- [ ] **Step 3: 라벨 도구를 만든다**

`src/main/java/project/study/metrics/report/Categories.java`:
```java
package project.study.metrics.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 공부 분야 코드(`users.category`) → 메시지 라벨. 코드 목록은 ProfileUpdateRequest의 허용 값과 같다. */
public final class Categories {

    public static final String NONE = "미입력";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("CSAT", "수능");
        LABELS.put("CIVIL_SERVICE", "공무원");
        LABELS.put("CERTIFICATE", "자격증");
        LABELS.put("JOB", "취업");
        LABELS.put("LANGUAGE", "어학");
        LABELS.put("PROFESSIONAL", "전문직");
        LABELS.put("ETC", "기타");
    }

    private static final List<String> ORDER = List.copyOf(LABELS.values());

    private Categories() {}

    public static String label(String category) {
        if (category == null || category.isBlank()) {
            return NONE;
        }
        return LABELS.getOrDefault(category, category);
    }

    /** 같은 수일 때의 표시 순서 — 정의 순, 모르는 값은 그 뒤, 미입력은 맨 끝. */
    public static int order(String label) {
        if (NONE.equals(label)) {
            return Integer.MAX_VALUE;
        }
        int index = ORDER.indexOf(label);
        return index < 0 ? ORDER.size() : index;
    }
}
```

`src/main/java/project/study/metrics/report/OsLabels.java`:
```java
package project.study.metrics.report;

import java.util.Locale;

/** Amplitude `os_name`(예: "ios", "Android") → 메시지 라벨. 값이 없으면 null. */
public final class OsLabels {

    public static final String MISSING = "미수집";

    private OsLabels() {}

    public static String label(String osName) {
        if (osName == null || osName.isBlank()) {
            return null;
        }
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("ios") || lower.contains("iphone") || lower.contains("ipad")) {
            return "iOS";
        }
        return lower.contains("android") ? "Android" : osName.strip();
    }
}
```

- [ ] **Step 4: 가입 분석을 만든다**

`src/main/java/project/study/metrics/report/analyzer/SignupSection.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import project.study.metrics.report.Rate;

/**
 * 🕐 가입 (스펙 §4.1).
 *
 * @param minUserId 어제 가입자 중 가장 작은 회원번호, 어제 가입이 없으면 null
 * @param bands 최근 7일 가입을 3시간 구간 8개로 나눈 것(00~03 … 21~24)
 */
public record SignupSection(
        LocalDate anchor,
        int yesterdayCount,
        Long minUserId,
        Long maxUserId,
        Rate yesterdayQuick,
        int weekTotal,
        List<HourBand> bands,
        List<Share> categories,
        Attribution attribution) {

    /** 바로 공부 비율을 비교할 최소 가입 수 — 1~2명 구간의 100%가 1위로 오르지 않게. */
    public static final int MIN_SIGNUPS_FOR_RATE = 5;

    public record HourBand(int startHour, int signups, int quick) {
        public Rate quickRate() {
            return Rate.of(quick, signups);
        }
    }

    public record Share(String label, int count) {}

    /** @param missing Amplitude 속성이 없는 유저 수 — 광고·UTM 판정에서 빠진다 */
    public record Attribution(int total, int adClicks, List<Share> topUtm, int missing) {}

    public LocalDate weekFrom() {
        return anchor.minusDays(6);
    }

    /** 가입이 가장 많은 구간(같으면 이른 구간). */
    public Optional<HourBand> peak() {
        HourBand peak = null;
        for (HourBand band : bands) {
            if (band.signups() > 0 && (peak == null || band.signups() > peak.signups())) {
                peak = band;
            }
        }
        return Optional.ofNullable(peak);
    }

    /** 가입 5명 이상 구간 중 바로 공부 비율이 가장 높은 구간(같으면 가입 많은 쪽, 그다음 이른 구간). 바로 공부가 0이면 없음. */
    public Optional<HourBand> bestQuick() {
        HourBand best = null;
        for (HourBand band : bands) {
            if (band.signups() >= MIN_SIGNUPS_FOR_RATE && band.quick() > 0 && (best == null || better(band, best))) {
                best = band;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean better(HourBand candidate, HourBand best) {
        long left = (long) candidate.quick() * best.signups();
        long right = (long) best.quick() * candidate.signups();
        return left > right || (left == right && candidate.signups() > best.signups());
    }
}
```

`src/main/java/project/study/metrics/report/analyzer/SignupAnalyzer.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.report.Categories;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.report.analyzer.SignupSection.Share;
import project.study.studysession.StudySessionThresholds;

/** 🕐 가입 (스펙 §4.1). */
public final class SignupAnalyzer {

    /**
     * 바로 공부 = 가입 시각부터 3시간 안에 시작한 세션 순공 합 10분 이상. "가입한 날 공부"로 세면 23시 가입자는 기회가 1시간이라
     * 시간대 비교가 기울고, 3시간 창은 어제 가입자도 리포트(10:00) 전에 모두 닫힌다.
     */
    static final Duration QUICK_WINDOW = Duration.ofHours(3);

    private static final int BANDS = 8;
    private static final int HOURS_PER_BAND = 3;
    private static final int TOP_UTM = 3;

    private SignupAnalyzer() {}

    public static SignupSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> yesterday = data.signedUpBetween(anchor, anchor);
        List<UserTimeline> week = data.signedUpBetween(anchor.minusDays(6), anchor);
        LongSummaryStatistics ids =
                yesterday.stream().mapToLong(UserTimeline::userId).summaryStatistics();
        int quick = (int) yesterday.stream().filter(SignupAnalyzer::quickStudy).count();
        return new SignupSection(
                anchor,
                yesterday.size(),
                yesterday.isEmpty() ? null : ids.getMin(),
                yesterday.isEmpty() ? null : ids.getMax(),
                Rate.of(quick, yesterday.size()),
                week.size(),
                bands(week),
                categories(week),
                attribution(week));
    }

    public static boolean quickStudy(UserTimeline user) {
        return user.focusStartedWithin(QUICK_WINDOW) >= StudySessionThresholds.MIN_STREAK_FOCUS_SEC;
    }

    private static List<HourBand> bands(List<UserTimeline> users) {
        int[] signups = new int[BANDS];
        int[] quick = new int[BANDS];
        for (UserTimeline user : users) {
            int band = Kst.hour(user.signupAt()) / HOURS_PER_BAND;
            signups[band]++;
            if (quickStudy(user)) {
                quick[band]++;
            }
        }
        return IntStream.range(0, BANDS)
                .mapToObj(band -> new HourBand(band * HOURS_PER_BAND, signups[band], quick[band]))
                .toList();
    }

    // 수 내림차순, 같으면 분야 정의 순, 미입력은 맨 끝
    private static List<Share> categories(List<UserTimeline> users) {
        Map<String, Integer> counts = new HashMap<>();
        users.forEach(user -> counts.merge(Categories.label(user.category()), 1, Integer::sum));
        return counts.entrySet().stream()
                .map(entry -> new Share(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing((Share share) -> Categories.NONE.equals(share.label()))
                        .thenComparing(Share::count, Comparator.reverseOrder())
                        .thenComparingInt(share -> Categories.order(share.label())))
                .toList();
    }

    private static Attribution attribution(List<UserTimeline> users) {
        int missing = 0;
        int adClicks = 0;
        Map<String, Integer> utm = new HashMap<>();
        for (UserTimeline user : users) {
            Optional<AmplitudeAttribute> attribute = user.attribute();
            if (attribute.isEmpty()) {
                missing++;
                continue;
            }
            adClicks += attribute.get().adClick() ? 1 : 0;
            String source = attribute.get().utmSource();
            if (source != null && !source.isBlank()) {
                utm.merge(source.strip(), 1, Integer::sum);
            }
        }
        List<Share> top = utm.entrySet().stream()
                .map(entry -> new Share(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Share::count, Comparator.reverseOrder())
                        .thenComparing(Share::label))
                .limit(TOP_UTM)
                .toList();
        return new Attribution(users.size(), adClicks, top, missing);
    }
}
```

- [ ] **Step 5: 리텐션 분석을 만든다**

`src/main/java/project/study/metrics/report/analyzer/RetentionSection.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;

/** 📈 리텐션 (스펙 §4.2). 행 순서는 다음 날 → 7~13일 뒤 → 28~34일 뒤. */
public record RetentionSection(List<RetentionRow> rows, Rate firstWeekNone, LocalDate noneFrom, LocalDate noneTo) {

    /** @param lastWeek 같은 계산을 기준일 A−7로 한 값 */
    public record RetentionRow(Window window, Rate current, Rate lastWeek, LocalDate cohortFrom, LocalDate cohortTo) {}
}
```

`src/main/java/project/study/metrics/report/analyzer/RetentionAnalyzer.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;

/**
 * 📈 리텐션 (스펙 §4.2) — 7·30일은 "딱 그날"이 아니라 7일 구간이다. 구간이 다 끝난 최근 28일치 가입자만 센다.
 */
public final class RetentionAnalyzer {

    public enum Window {
        NEXT_DAY(1, 1),
        WEEK_TWO(7, 13),
        WEEK_FIVE(28, 34);

        private final int fromDay;
        private final int toDay;

        Window(int fromDay, int toDay) {
            this.fromDay = fromDay;
            this.toDay = toDay;
        }

        public int fromDay() {
            return fromDay;
        }

        public int toDay() {
            return toDay;
        }
    }

    private static final int COHORT_DAYS = 28;
    private static final int LAST_WEEK = 7;
    private static final int FIRST_WEEK_NEWEST = 6;
    private static final int FIRST_WEEK_OLDEST = FIRST_WEEK_NEWEST + COHORT_DAYS - 1;

    private RetentionAnalyzer() {}

    public static RetentionSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<RetentionRow> rows = Arrays.stream(Window.values())
                .map(window -> new RetentionRow(
                        window,
                        rate(data, anchor, window),
                        rate(data, anchor.minusDays(LAST_WEEK), window),
                        cohortFrom(anchor, window),
                        cohortTo(anchor, window)))
                .toList();
        LocalDate noneFrom = anchor.minusDays(FIRST_WEEK_OLDEST);
        LocalDate noneTo = anchor.minusDays(FIRST_WEEK_NEWEST);
        List<UserTimeline> cohort = data.signedUpBetween(noneFrom, noneTo);
        int none = (int) cohort.stream().filter(user -> Cohorts.firstWeekStudyDays(user) == 0).count();
        return new RetentionSection(rows, Rate.of(none, cohort.size()), noneFrom, noneTo);
    }

    static Rate rate(ReportData data, LocalDate anchor, Window window) {
        List<UserTimeline> cohort = data.signedUpBetween(cohortFrom(anchor, window), cohortTo(anchor, window));
        int retained = (int) cohort.stream()
                .filter(user -> user.studyDays(user.day(window.fromDay()), user.day(window.toDay())) >= 1)
                .count();
        return Rate.of(retained, cohort.size());
    }

    static LocalDate cohortTo(LocalDate anchor, Window window) {
        return anchor.minusDays(window.toDay());
    }

    static LocalDate cohortFrom(LocalDate anchor, Window window) {
        return anchor.minusDays(window.toDay() + COHORT_DAYS - 1L);
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.analyzer.*"`
Expected: PASS (8 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/project/study/metrics/report src/test/java/project/study/metrics/report/analyzer
git commit -m "feat: 가입·리텐션 섹션을 계산한다 (BY-904)"
```

---
### Task 6: 첫 주 공부 일수별 비교와 명단

**Files:**
- Create: `src/main/java/project/study/metrics/report/Profiles.java`, `Attributions.java`
- Create: `src/main/java/project/study/metrics/report/analyzer/SegmentSection.java`, `SegmentAnalyzer.java`, `SegmentProfiles.java`,
  `RosterSection.java`, `RosterCard.java`, `RosterCards.java`, `SegmentRoster.java`
- Test: `src/test/java/project/study/metrics/report/analyzer/SegmentAnalyzerTest.java`, `SegmentRosterTest.java`

**Interfaces:**
- Consumes: Task 2·4·5 (`Cohorts`, `FirstWeekHabits`, `RuleCatalog`, `CohortStats`, `TwoByTwo`, `Categories`, `OsLabels`, `Kst.bandLabel`)
- Produces:
  - `Profiles.nickname(UserTimeline)`("닉네임 없음" 대체), `Profiles.label(UserTimeline)`("공무원(7급 합격)", 분야 없으면 "분야 미입력"),
    `Profiles.filled(UserTimeline)`(분야·목표 중 하나라도)
  - `Attributions.label(UserTimeline)`("유입: 광고(instagram)" / "유입: 초대 링크" / "유입: tiktok" / "유입: 표시 없음" / "유입 미수집"),
    `Attributions.adClick(UserTimeline): boolean`
  - `record SegmentSection(LocalDate cohortFrom, LocalDate cohortTo, int cohortSize, List<GroupRow> groups, TwoByTwo table,
    boolean enoughForBehaviors, boolean amplitudeCollected, List<BehaviorRow> behaviors, BehaviorRow profile, List<Comparison> who)` —
    중첩 `GroupRow(String label, int size, Rate retained)`, `BehaviorRow(String label, Rate segment, Rate rest)`,
    `ShareRow(String label, Rate segment, Rate rest)`, `Comparison(String title, List<ShareRow> rows)`; `SegmentAnalyzer.analyze(ReportData)`
  - `record RosterSection(LocalDate anchor, LocalDate cohortFrom, LocalDate cohortTo, RosterGroup retained, RosterGroup dropped)` —
    중첩 `RosterGroup(int total, List<RosterCard> cards, LocalDate omittedSignupOnOrBefore)`(`omitted()`), `total()`;
    `SegmentRoster.analyze(ReportData)`, `SegmentRoster.CARD_LIMIT = 30`
  - `record RosterCard(boolean fresh, long userId, String nickname, Instant signupAt, String profile, String os, String attribution,
    StudyFlow flow, List<String> actions, boolean amplitudePartial, Habits habits)` — 중첩 `StudyFlow(int firstWeekDays,
    int firstWeekFocusSec, int firstWeekLongestRun, int secondWeekDays, int secondWeekFocusSec, LocalDate lastStudyDay,
    boolean lastInFirstWeek, int currentStreak)`, `Habits(OptionalDouble averageSessionFocusSec, OptionalInt topBandStartHour,
    OptionalInt socialPercent, OptionalDouble pausesPerSession, OptionalDouble distractionsPerHour)`

- [ ] **Step 1: 테스트를 쓴다**

`src/test/java/project/study/metrics/report/analyzer/SegmentAnalyzerTest.java`:
```java
package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.TimelineBuilder;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;

class SegmentAnalyzerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final LocalDate SIGNUP = A.minusDays(20);

    /** 첫 주 3일+ 6명(4명 남음) · 1~2일 5명(1명 남음) · 0일 10명. 3일+는 모두 방 3번, 3명은 D-day. */
    private static List<UserTimeline> cohort() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            TimelineBuilder builder = user(100 + i, SIGNUP)
                    .profile("s" + i, i < 4 ? "CSAT" : i == 4 ? "CIVIL_SERVICE" : null, null)
                    .studyOnDays(0, 2, 4)
                    .activity(Activity.ROOM_JOIN, 1, 3)
                    .amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1);
            if (i < 3) {
                builder.activity(Activity.DDAY_SET, 0, 1);
            }
            if (i < 4) {
                builder.studyOnDays(8);
            }
            users.add(builder.build());
        }
        for (int i = 0; i < 5; i++) {
            TimelineBuilder builder = user(200 + i, SIGNUP).studyOnDay(1, 12);
            users.add(i == 0 ? builder.studyOnDay(9, 12).build() : builder.build());
        }
        for (int i = 0; i < 10; i++) {
            users.add(user(300 + i, SIGNUP).build());
        }
        users.add(user(400, A.minusDays(12)).studyOnDays(0, 1, 2).build()); // 둘째 주가 안 끝났다 — 코호트 밖
        return users;
    }

    @Test
    void 코호트를_첫_주_공부한_날로_세_묶음으로_나눠_둘째_주를_비교한다() {
        SegmentSection section = SegmentAnalyzer.analyze(
                ReportDataFixture.data(A, false, cohort().toArray(UserTimeline[]::new)));

        assertThat(section.cohortFrom()).isEqualTo(A.minusDays(40));
        assertThat(section.cohortTo()).isEqualTo(A.minusDays(13));
        assertThat(section.cohortSize()).isEqualTo(21);
        assertThat(section.groups())
                .containsExactly(
                        new GroupRow("3일 이상", 6, Rate.of(4, 6)),
                        new GroupRow("1~2일", 5, Rate.of(1, 5)),
                        new GroupRow("0일", 10, Rate.of(0, 10)));
        assertThat(section.table().doerRetention()).isEqualTo(Rate.of(4, 6));
        assertThat(section.table().nonDoerRetention()).isEqualTo(Rate.of(1, 15));
    }

    @Test
    void 주로_한_행동은_3일_이상의_30퍼센트_이상이_한_것_중_차이가_큰_순_5개다() {
        SegmentSection section = SegmentAnalyzer.analyze(
                ReportDataFixture.data(A, false, cohort().toArray(UserTimeline[]::new)));

        assertThat(section.enoughForBehaviors()).isTrue();
        assertThat(section.amplitudeCollected()).isFalse();
        assertThat(section.behaviors())
                .extracting(BehaviorRow::label)
                .containsExactly(
                        "소셜 방 1번 이상 참여",
                        "소셜 방 3번 이상 참여",
                        "세션 평균 순공 30분 이상",
                        "세션당 일시정지가 적음(중앙값 이하)",
                        "D-day 설정");
        assertThat(section.profile()).isEqualTo(new BehaviorRow("프로필(분야·목표) 입력", Rate.of(5, 6), Rate.of(0, 15)));
        assertThat(section.who().getFirst().title()).isEqualTo("분야");
        assertThat(section.who().getFirst().rows())
                .extracting(ShareRow::label)
                .containsExactly("수능", "공무원", "미입력");
    }

    @Test
    void Amplitude가_수집됐으면_앱_행동도_후보다() {
        SegmentSection section = SegmentAnalyzer.analyze(
                ReportDataFixture.data(A, true, cohort().toArray(UserTimeline[]::new)));

        assertThat(section.amplitudeCollected()).isTrue();
        assertThat(section.behaviors()).extracting(BehaviorRow::label).contains("간단 모드 켬");
    }

    @Test
    void 첫_주_3일_이상이_5명_미만이면_행동과_특징_비교를_하지_않는다() {
        SegmentSection section = SegmentAnalyzer.analyze(ReportDataFixture.data(
                A, false, user(1, SIGNUP).studyOnDays(0, 1, 2).build(), user(2, SIGNUP).build()));

        assertThat(section.enoughForBehaviors()).isFalse();
        assertThat(section.behaviors()).isEmpty();
        assertThat(section.who()).isEmpty();
    }
}
```

`src/test/java/project/study/metrics/report/analyzer/SegmentRosterTest.java`:
```java
package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;

class SegmentRosterTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final LocalDate FRESH = A.minusDays(13);

    private static UserTimeline bear() {
        return user(418, FRESH)
                .signupHour(9)
                .profile("공부하는곰", "CIVIL_SERVICE", "7급 합격")
                .attribute("instagram", true, "ios")
                .studyOnDay(0, 60)
                .studyOnDay(1, 60)
                .studyOnDay(2, 60)
                .studyOnDay(3, 60)
                .studyOnDay(5, 60)
                .studyOnDays(10, 11, 12, 13)
                .activity(Activity.ROOM_JOIN, 1, 7)
                .subject(0, null)
                .subject(0, null)
                .subject(0, null)
                .subject(0, 3)
                .activity(Activity.TASK_DONE, 2, 12)
                .activity(Activity.DDAY_SET, 0, 1)
                .amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1)
                .amp(AmplitudeKeys.LANDSCAPE, 2, 1)
                .amp(AmplitudeKeys.AMBIENT_SOUND, 3, 2)
                .amp(AmplitudeKeys.RANKING, 4, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 0, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 1, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 2, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 3, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 4, 1)
                .amp(AmplitudeKeys.APP_LAUNCHED, 5, 2)
                .build();
    }

    @Test
    void 첫_주_3일_이상을_남은_사람과_떠난_사람으로_나눠_최신_가입부터_보여준다() {
        UserTimeline older = user(409, A.minusDays(20)).studyOnDays(0, 1, 2, 8).build();
        UserTimeline left = user(421, FRESH).studyOnDays(0, 1, 2).build();
        UserTimeline light = user(500, FRESH).studyOnDays(0, 8).build(); // 첫 주 1일 — 명단 밖

        RosterSection section = SegmentRoster.analyze(ReportDataFixture.data(A, true, older, bear(), left, light));

        assertThat(section.total()).isEqualTo(3);
        assertThat(section.retained().cards()).extracting(RosterCard::userId).containsExactly(418L, 409L);
        assertThat(section.retained().cards()).extracting(RosterCard::fresh).containsExactly(true, false);
        assertThat(section.dropped().cards()).singleElement().satisfies(card -> {
            assertThat(card.fresh()).isTrue();
            assertThat(card.attribution()).isEqualTo("유입 미수집");
            assertThat(card.flow().secondWeekDays()).isZero();
            assertThat(card.flow().lastStudyDay()).isEqualTo(FRESH.plusDays(2));
            assertThat(card.flow().lastInFirstWeek()).isTrue();
            assertThat(card.flow().currentStreak()).isZero();
        });
    }

    @Test
    void 카드는_누구_공부_흐름_첫_주에_한_일_습관을_담는다() {
        RosterCard card = SegmentRoster.analyze(ReportDataFixture.data(A, true, bear()))
                .retained()
                .cards()
                .getFirst();

        assertThat(card.nickname()).isEqualTo("공부하는곰");
        assertThat(card.profile()).isEqualTo("공무원(7급 합격)");
        assertThat(card.os()).isEqualTo("iOS");
        assertThat(card.attribution()).isEqualTo("유입: 광고(instagram)");
        assertThat(card.flow()).isEqualTo(new StudyFlow(5, 5 * 3600, 4, 4, 4 * 1800, A, false, 4));
        assertThat(card.actions())
                .containsExactly(
                        "소셜 방 7회", "과목 4개", "할 일 완료 12개", "D-day", "간단 모드", "가로 화면", "백색소음", "랭킹", "앱 실행 6일");
        assertThat(card.amplitudePartial()).isFalse();
        assertThat(card.habits().averageSessionFocusSec().getAsDouble()).isEqualTo(3600);
        assertThat(card.habits().topBandStartHour().getAsInt()).isEqualTo(18);
        assertThat(card.habits().socialPercent().getAsInt()).isZero();
        assertThat(card.habits().distractionsPerHour().getAsDouble()).isZero();
    }

    @Test
    void Amplitude가_빠진_기간이면_표시하고_딴짓은_비운다() {
        RosterCard card = SegmentRoster.analyze(ReportDataFixture.data(A, false, bear()))
                .retained()
                .cards()
                .getFirst();

        assertThat(card.amplitudePartial()).isTrue();
        assertThat(card.habits().distractionsPerHour()).isEmpty();
    }

    @Test
    void 묶음마다_30명까지만_카드로_만들고_나머지는_가입일로_알린다() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            users.add(user(1000 + i, A.minusDays(13 + i % 28)).studyOnDays(0, 1, 2, 8).build());
        }

        RosterSection section = SegmentRoster.analyze(ReportDataFixture.data(A, false, users.toArray(UserTimeline[]::new)));

        assertThat(section.retained().total()).isEqualTo(31);
        assertThat(section.retained().cards()).hasSize(30);
        assertThat(section.retained().omitted()).isEqualTo(1);
        assertThat(section.retained().omittedSignupOnOrBefore()).isEqualTo(A.minusDays(40));
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.analyzer.Segment*"`
Expected: FAIL — `cannot find symbol: class SegmentAnalyzer`

- [ ] **Step 3: 프로필·유입 라벨을 만든다**

`src/main/java/project/study/metrics/report/Profiles.java`:
```java
package project.study.metrics.report;

/** 카드·한 줄 프로필의 이름과 "분야(목표)" 라벨. 목표는 자유 입력이라 입력한 경우만 괄호로 붙인다 (스펙 §4.6). */
public final class Profiles {

    public static final String NO_NICKNAME = "닉네임 없음";
    public static final String NO_CATEGORY = "분야 미입력";

    private Profiles() {}

    public static String nickname(UserTimeline user) {
        return hasText(user.nickname()) ? user.nickname().strip() : NO_NICKNAME;
    }

    public static String label(UserTimeline user) {
        String category = hasText(user.category()) ? Categories.label(user.category()) : NO_CATEGORY;
        return hasText(user.goal()) ? category + "(" + user.goal().strip() + ")" : category;
    }

    /** 프로필 입력 = 분야·목표 중 하나라도. 입력 시점 기록이 없어 "지금 기준"이다. */
    public static boolean filled(UserTimeline user) {
        return hasText(user.category()) || hasText(user.goal());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

`src/main/java/project/study/metrics/report/Attributions.java`:
```java
package project.study.metrics.report;

import java.util.Optional;
import project.study.metrics.amplitude.AmplitudeAttribute;

/** 카드의 유입 라벨 (스펙 §4.4) — 광고 클릭 > 가입 이틀 안 초대 링크 > UTM > 표시 없음. Amplitude 속성이 없으면 미수집. */
public final class Attributions {

    private Attributions() {}

    public static String label(UserTimeline user) {
        Optional<AmplitudeAttribute> attribute = user.attribute();
        if (attribute.isEmpty()) {
            return "유입 미수집";
        }
        String utm = attribute.get().utmSource();
        String source = utm == null || utm.isBlank() ? null : utm.strip();
        if (attribute.get().adClick()) {
            return "유입: 광고(" + (source == null ? "소스 없음" : source) + ")";
        }
        if (user.amplitudeCount(AmplitudeKeys.INVITE_OPENED, user.day(0), user.day(1)) > 0) {
            return "유입: 초대 링크";
        }
        return source == null ? "유입: 표시 없음" : "유입: " + source;
    }

    public static boolean adClick(UserTimeline user) {
        return user.attribute().map(AmplitudeAttribute::adClick).orElse(false);
    }
}
```

- [ ] **Step 4: 세그먼트 비교를 만든다**

`src/main/java/project/study/metrics/report/analyzer/SegmentSection.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.rule.TwoByTwo;

/**
 * 👥 첫 주 공부 일수별 비교 (스펙 §4.3). {@code table}은 "3일 이상 vs 나머지"의 2×2 표다.
 *
 * @param enoughForBehaviors 3일 이상이 5명 이상이라 행동·특징 비교를 하는지
 * @param amplitudeCollected 코호트 첫 주 기간 Amplitude가 다 수집돼 앱 행동도 후보에 넣었는지
 * @param profile 6번째 줄 "프로필(분야·목표) 입력" — 입력 시점 기록이 없어 지금 기준이다
 */
public record SegmentSection(
        LocalDate cohortFrom,
        LocalDate cohortTo,
        int cohortSize,
        List<GroupRow> groups,
        TwoByTwo table,
        boolean enoughForBehaviors,
        boolean amplitudeCollected,
        List<BehaviorRow> behaviors,
        BehaviorRow profile,
        List<Comparison> who) {

    public record GroupRow(String label, int size, Rate retained) {}

    public record BehaviorRow(String label, Rate segment, Rate rest) {}

    public record ShareRow(String label, Rate segment, Rate rest) {}

    public record Comparison(String title, List<ShareRow> rows) {}
}
```

`src/main/java/project/study/metrics/report/analyzer/SegmentAnalyzer.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.Profiles;
import project.study.metrics.report.Rate;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.rule.CohortStats;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.TwoByTwo;

/** 👥 첫 주 공부 일수별 비교 (스펙 §4.3). */
public final class SegmentAnalyzer {

    static final int MIN_SEGMENT = 5;
    static final int MIN_BEHAVIOR_PERCENT = 30;
    static final int BEHAVIOR_LIMIT = 5;

    private SegmentAnalyzer() {}

    public static SegmentSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> cohort = Cohorts.weekTwo(data, anchor);
        List<UserTimeline> segment = cohort.stream().filter(Cohorts::fixedSegment).toList();
        List<UserTimeline> rest =
                cohort.stream().filter(user -> !Cohorts.fixedSegment(user)).toList();
        boolean amplitude = Cohorts.firstWeeksCollected(data, anchor);
        boolean enough = segment.size() >= MIN_SEGMENT;
        return new SegmentSection(
                Cohorts.weekTwoFrom(anchor),
                Cohorts.weekTwoTo(anchor),
                cohort.size(),
                groups(cohort),
                TwoByTwo.of(cohort, Cohorts::fixedSegment, Cohorts::retainedWeekTwo),
                enough,
                amplitude,
                enough ? behaviors(cohort, segment, rest, amplitude) : List.of(),
                new BehaviorRow("프로필(분야·목표) 입력", rate(segment, Profiles::filled), rate(rest, Profiles::filled)),
                enough ? SegmentProfiles.compare(segment, rest) : List.of());
    }

    static Rate rate(List<UserTimeline> users, Predicate<UserTimeline> test) {
        return Rate.of((int) users.stream().filter(test).count(), users.size());
    }

    private static List<GroupRow> groups(List<UserTimeline> cohort) {
        return List.of(
                group("3일 이상", cohort, days -> days >= Cohorts.FIXED_SEGMENT_DAYS),
                group("1~2일", cohort, days -> days >= 1 && days < Cohorts.FIXED_SEGMENT_DAYS),
                group("0일", cohort, days -> days == 0));
    }

    private static GroupRow group(String label, List<UserTimeline> cohort, IntPredicate firstWeekDays) {
        List<UserTimeline> members = cohort.stream()
                .filter(user -> firstWeekDays.test(Cohorts.firstWeekStudyDays(user)))
                .toList();
        return new GroupRow(label, members.size(), rate(members, Cohorts::retainedWeekTwo));
    }

    // 3일 이상의 30% 이상이 한 행동 중 (3일 이상 사용률 − 나머지 사용률)이 큰 순. 같으면 카탈로그 순(안정 정렬)
    private static List<BehaviorRow> behaviors(
            List<UserTimeline> cohort, List<UserTimeline> segment, List<UserTimeline> rest, boolean amplitude) {
        CohortStats stats = CohortStats.of(cohort);
        return RuleCatalog.behaviors().stream()
                .filter(rule -> amplitude || !rule.amplitude())
                .map(rule -> row(rule, stats, segment, rest))
                .filter(row -> row.segment().atLeastPercent(MIN_BEHAVIOR_PERCENT))
                .sorted(Comparator.comparingDouble((BehaviorRow row) -> row.segment().ratio() - row.rest().ratio())
                        .reversed())
                .limit(BEHAVIOR_LIMIT)
                .toList();
    }

    private static BehaviorRow row(
            SegmentRule rule, CohortStats stats, List<UserTimeline> segment, List<UserTimeline> rest) {
        Predicate<UserTimeline> did = user -> rule.matches(user, stats);
        return new BehaviorRow(rule.phrase(), rate(segment, did), rate(rest, did));
    }
}
```

`src/main/java/project/study/metrics/report/analyzer/SegmentProfiles.java`:
```java
package project.study.metrics.report.analyzer;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import project.study.metrics.report.Attributions;
import project.study.metrics.report.Categories;
import project.study.metrics.report.Kst;
import project.study.metrics.report.OsLabels;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;

/** "어떤 사람인가" — 3일 이상과 나머지의 분야·가입 시간대·OS·광고 유입 비율을 나란히 (스펙 §4.3). */
final class SegmentProfiles {

    private static final int TOP_CATEGORIES = 3;
    private static final int TOP_BANDS = 2;
    private static final int HOURS_PER_BAND = 3;
    private static final String OTHER_OS = "기타";
    private static final List<String> OS_ORDER = List.of("iOS", "Android", OTHER_OS, OsLabels.MISSING);

    private SegmentProfiles() {}

    static List<Comparison> compare(List<UserTimeline> segment, List<UserTimeline> rest) {
        return List.of(
                categories(segment, rest),
                bands(segment, rest),
                os(segment, rest),
                new Comparison("광고 유입", List.of(row("", segment, rest, Attributions::adClick))));
    }

    // 3일 이상에서 많은 분야 3개 + 미입력
    private static Comparison categories(List<UserTimeline> segment, List<UserTimeline> rest) {
        Function<UserTimeline, String> label = user -> Categories.label(user.category());
        Map<String, Long> counts = segment.stream().collect(groupingBy(label, counting()));
        List<String> labels = new ArrayList<>(counts.keySet().stream()
                .filter(name -> !Categories.NONE.equals(name))
                .sorted(Comparator.comparing((String name) -> counts.get(name), Comparator.reverseOrder())
                        .thenComparingInt(Categories::order))
                .limit(TOP_CATEGORIES)
                .toList());
        labels.add(Categories.NONE);
        return new Comparison(
                "분야",
                labels.stream()
                        .map(name -> row(name, segment, rest, user -> label.apply(user).equals(name)))
                        .toList());
    }

    private static Comparison bands(List<UserTimeline> segment, List<UserTimeline> rest) {
        Function<UserTimeline, Integer> band = user -> Kst.hour(user.signupAt()) / HOURS_PER_BAND;
        Map<Integer, Long> counts = segment.stream().collect(groupingBy(band, counting()));
        List<ShareRow> rows = counts.keySet().stream()
                .sorted(Comparator.comparing((Integer key) -> counts.get(key), Comparator.reverseOrder())
                        .thenComparing(Comparator.naturalOrder()))
                .limit(TOP_BANDS)
                .map(key -> row(
                        Kst.bandLabel(key * HOURS_PER_BAND), segment, rest, user -> band.apply(user).equals(key)))
                .toList();
        return new Comparison("가입 시간대", rows);
    }

    private static Comparison os(List<UserTimeline> segment, List<UserTimeline> rest) {
        Set<String> present = Stream.concat(segment.stream(), rest.stream())
                .map(SegmentProfiles::osGroup)
                .collect(toSet());
        return new Comparison(
                "OS",
                OS_ORDER.stream()
                        .filter(present::contains)
                        .map(name -> row(name, segment, rest, user -> osGroup(user).equals(name)))
                        .toList());
    }

    private static String osGroup(UserTimeline user) {
        String os = user.attribute().map(attribute -> OsLabels.label(attribute.osName())).orElse(null);
        if (os == null) {
            return OsLabels.MISSING;
        }
        return OS_ORDER.contains(os) ? os : OTHER_OS;
    }

    private static ShareRow row(
            String label, List<UserTimeline> segment, List<UserTimeline> rest, Predicate<UserTimeline> test) {
        return new ShareRow(label, SegmentAnalyzer.rate(segment, test), SegmentAnalyzer.rate(rest, test));
    }
}
```

- [ ] **Step 5: 명단을 만든다**

`src/main/java/project/study/metrics/report/analyzer/RosterCard.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * 명단 카드 한 장 (스펙 §4.4) — 누구 · 공부 흐름 · 첫 주에 한 일 · 습관.
 *
 * @param fresh 어제로 둘째 주가 끝나 새로 들어온 사람(가입일 A−13)
 * @param os OS 라벨, 모르면 null
 * @param actions 첫 주에 한 행동(한 것만), 순서 고정
 * @param amplitudePartial 첫 주에 Amplitude 미수집일이 있다
 */
public record RosterCard(
        boolean fresh,
        long userId,
        String nickname,
        Instant signupAt,
        String profile,
        String os,
        String attribution,
        StudyFlow flow,
        List<String> actions,
        boolean amplitudePartial,
        Habits habits) {

    /**
     * @param lastStudyDay A까지의 마지막 공부한 날, 없으면 null
     * @param lastInFirstWeek 마지막 공부한 날이 첫 주 안이다
     * @param currentStreak A부터 거꾸로 센 연속 공부한 날
     */
    public record StudyFlow(
            int firstWeekDays,
            int firstWeekFocusSec,
            int firstWeekLongestRun,
            int secondWeekDays,
            int secondWeekFocusSec,
            LocalDate lastStudyDay,
            boolean lastInFirstWeek,
            int currentStreak) {}

    public record Habits(
            OptionalDouble averageSessionFocusSec,
            OptionalInt topBandStartHour,
            OptionalInt socialPercent,
            OptionalDouble pausesPerSession,
            OptionalDouble distractionsPerHour) {}
}
```

`src/main/java/project/study/metrics/report/analyzer/RosterCards.java`:
```java
package project.study.metrics.report.analyzer;

import static project.study.metrics.report.UserTimeline.Activity.DDAY_SET;
import static project.study.metrics.report.UserTimeline.Activity.ROOM_JOIN;
import static project.study.metrics.report.UserTimeline.Activity.SUBJECT_CREATED;
import static project.study.metrics.report.UserTimeline.Activity.TASK_DONE;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.Attributions;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.FirstWeekHabits;
import project.study.metrics.report.OsLabels;
import project.study.metrics.report.Profiles;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.RosterCard.Habits;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;
import project.study.studysession.StudySessionThresholds;

/** 유저 한 명 → 명단 카드. */
final class RosterCards {

    // 이름만 쓰는 Amplitude 행동 — 표시 순서 그대로
    private static final List<Map.Entry<String, String>> AMPLITUDE_ACTIONS = List.of(
            Map.entry("간단 모드", AmplitudeKeys.SIMPLE_MODE_ON),
            Map.entry("가로 화면", AmplitudeKeys.LANDSCAPE),
            Map.entry("카메라", AmplitudeKeys.CAMERA_ON),
            Map.entry("백색소음", AmplitudeKeys.AMBIENT_SOUND),
            Map.entry("플래너", AmplitudeKeys.PLANNER),
            Map.entry("랭킹", AmplitudeKeys.RANKING),
            Map.entry("초대 공유", AmplitudeKeys.INVITE_SHARED),
            Map.entry("가이드 완료", AmplitudeKeys.GUIDE_FINISHED),
            Map.entry("기록 날짜 보기", AmplitudeKeys.RECORDS_DATE),
            Map.entry("푸시 열기", AmplitudeKeys.PUSH_OPENED));
    private static final int HOURS_PER_BAND = 3;

    private RosterCards() {}

    static RosterCard of(ReportData data, UserTimeline user) {
        LocalDate anchor = data.anchor();
        boolean amplitude = data.amplitudeCollected(user.day(0), user.day(6));
        return new RosterCard(
                user.signupDate().equals(Cohorts.weekTwoTo(anchor)),
                user.userId(),
                Profiles.nickname(user),
                user.signupAt(),
                Profiles.label(user),
                user.attribute().map(attribute -> OsLabels.label(attribute.osName())).orElse(null),
                Attributions.label(user),
                flow(user, anchor),
                actions(user),
                !amplitude,
                habits(user, amplitude));
    }

    private static StudyFlow flow(UserTimeline user, LocalDate anchor) {
        LocalDate last = user.lastStudyDay(anchor).orElse(null);
        return new StudyFlow(
                Cohorts.firstWeekStudyDays(user),
                user.focusSec(user.day(0), user.day(6)),
                user.longestRun(user.day(0), user.day(6), StudySessionThresholds.MIN_STREAK_FOCUS_SEC),
                user.studyDays(user.day(7), user.day(13)),
                user.focusSec(user.day(7), user.day(13)),
                last,
                last != null && !last.isAfter(user.day(6)),
                user.streakEndingAt(anchor));
    }

    static List<String> actions(UserTimeline user) {
        LocalDate from = user.day(0);
        LocalDate to = user.day(6);
        List<String> actions = new ArrayList<>();
        counted(actions, "소셜 방 %d회", user.activityCount(ROOM_JOIN, from, to));
        counted(actions, "과목 %d개", user.activityCount(SUBJECT_CREATED, from, to));
        counted(actions, "할 일 완료 %d개", user.activityCount(TASK_DONE, from, to));
        if (user.activityCount(DDAY_SET, from, to) > 0) {
            actions.add("D-day");
        }
        for (Map.Entry<String, String> action : AMPLITUDE_ACTIONS) {
            if (user.amplitudeCount(action.getValue(), from, to) > 0) {
                actions.add(action.getKey());
            }
        }
        counted(actions, "앱 실행 %d일", user.amplitudeDays(AmplitudeKeys.APP_LAUNCHED, from, to));
        return actions;
    }

    private static void counted(List<String> actions, String format, int count) {
        if (count > 0) {
            actions.add(format.formatted(count));
        }
    }

    // 딴짓은 Amplitude 값이라 첫 주가 다 수집됐을 때만 쓴다 — 빠진 날을 0으로 세면 "딴짓 적음"으로 보인다
    private static Habits habits(UserTimeline user, boolean amplitude) {
        OptionalInt band = FirstWeekHabits.topStartBand(user);
        return new Habits(
                FirstWeekHabits.averageSessionFocusSec(user),
                band.isPresent() ? OptionalInt.of(band.getAsInt() * HOURS_PER_BAND) : OptionalInt.empty(),
                FirstWeekHabits.socialFocusPercent(user),
                FirstWeekHabits.pausesPerSession(user),
                amplitude ? FirstWeekHabits.distractionsPerFocusHour(user) : OptionalDouble.empty());
    }
}
```

`src/main/java/project/study/metrics/report/analyzer/RosterSection.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;

/** 🙋 첫 주 3일 이상 명단 (스펙 §4.4) — 요약의 "N명 중 k명" 뒤에 있는 사람 그대로다. */
public record RosterSection(
        LocalDate anchor, LocalDate cohortFrom, LocalDate cohortTo, RosterGroup retained, RosterGroup dropped) {

    /** @param omittedSignupOnOrBefore 카드 상한으로 빠진 사람 중 가장 최근 가입일, 빠진 사람이 없으면 null */
    public record RosterGroup(int total, List<RosterCard> cards, LocalDate omittedSignupOnOrBefore) {
        public int omitted() {
            return total - cards.size();
        }
    }

    public int total() {
        return retained.total() + dropped.total();
    }
}
```

`src/main/java/project/study/metrics/report/analyzer/SegmentRoster.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;

/** 🙋 첫 주 3일 이상 명단 (스펙 §4.4). 묶음 안은 가입 최신 순 — 매일은 맨 위 🆕만 보면 된다. */
public final class SegmentRoster {

    public static final int CARD_LIMIT = 30;

    private static final Comparator<UserTimeline> NEWEST_FIRST = Comparator.comparing(UserTimeline::signupAt)
            .reversed()
            .thenComparing(UserTimeline::userId);

    private SegmentRoster() {}

    public static RosterSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> segment = Cohorts.weekTwo(data, anchor).stream()
                .filter(Cohorts::fixedSegment)
                .sorted(NEWEST_FIRST)
                .toList();
        return new RosterSection(
                anchor,
                Cohorts.weekTwoFrom(anchor),
                Cohorts.weekTwoTo(anchor),
                group(data, segment.stream().filter(Cohorts::retainedWeekTwo).toList()),
                group(data, segment.stream().filter(user -> !Cohorts.retainedWeekTwo(user)).toList()));
    }

    private static RosterGroup group(ReportData data, List<UserTimeline> users) {
        List<RosterCard> cards =
                users.stream().limit(CARD_LIMIT).map(user -> RosterCards.of(data, user)).toList();
        LocalDate omittedFrom = users.size() > CARD_LIMIT ? users.get(CARD_LIMIT).signupDate() : null;
        return new RosterGroup(users.size(), cards, omittedFrom);
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.analyzer.Segment*"`
Expected: PASS (8 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/project/study/metrics/report src/test/java/project/study/metrics/report/analyzer
git commit -m "feat: 첫 주 공부 일수별 비교와 명단을 계산한다 (BY-904)"
```

---
### Task 7: 자동 탐색과 어제 공부한 유저

**Files:**
- Create: `src/main/java/project/study/metrics/report/analyzer/ExplorerSection.java`, `RuleExplorer.java`,
  `ActiveUsersSection.java`, `ActiveUserClassifier.java`
- Test: `src/test/java/project/study/metrics/report/analyzer/RuleExplorerTest.java`, `ActiveUserClassifierTest.java`

**Interfaces:**
- Consumes: Task 2·4·6 (`Cohorts`, `RuleCatalog`, `CohortStats`, `TwoByTwo`, `Exposure`, `Strength`, `Profiles`, `AmplitudeKeys`)
- Produces:
  - `record ExplorerSection(LocalDate cohortFrom, LocalDate cohortTo, int cohortSize, int retained, List<RankedRule> top,
    Map<Exposure, Integer> tally, Exposure fixedRuleExposure)` — `passed()`, `ruleCount()`; 중첩
    `RankedRule(SegmentRule rule, TwoByTwo table, int rank, int days)`(`fixed()`, `strength()`)
  - `RuleExplorer.analyze(ReportData): ExplorerSection`, `RuleExplorer.days(String key, int rank, List<List<String>> previousTops): int`
  - `record ActiveUsersSection(int total, Map<Stage, List<ActiveUserRow>> byStage, Integer appOnly)` — `count(Stage)`;
    중첩 `enum Stage { NEW, STEADY, RETURNING, OCCASIONAL }`(`label()`, 선언 순서 = 표시 순서),
    `ActiveUserRow(long userId, String nickname, long daysSinceSignup, int streak, int focusSec, boolean socialMostly, int liveSubjects,
    String profile, Integer appDays7)`
  - `ActiveUserClassifier.analyze(ReportData)`, `ActiveUserClassifier.stage(UserTimeline, LocalDate): Stage`

- [ ] **Step 1: 테스트를 쓴다**

`src/test/java/project/study/metrics/report/analyzer/RuleExplorerTest.java`:
```java
package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.TimelineBuilder;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.Strength;

class RuleExplorerTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);
    private static final LocalDate SIGNUP = A.minusDays(20); // A·A−1·A−2 세 코호트에 모두 든다

    /** 30명 — 10명은 첫 주 방 3번(7명 남음), 20명은 안 함(2명 남음). 모두 첫 주 하루 30분만 공부. */
    private static UserTimeline[] cohort() {
        List<UserTimeline> users = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean roomUser = i < 10;
            boolean retained = roomUser ? i < 7 : i < 12;
            TimelineBuilder builder = user(100 + i, SIGNUP).studyOnDays(0);
            if (roomUser) {
                builder.activity(Activity.ROOM_JOIN, 1, 3).amp(AmplitudeKeys.SIMPLE_MODE_ON, 1, 1);
            }
            if (retained) {
                builder.studyOnDays(8);
            }
            users.add(builder.build());
        }
        return users.toArray(UserTimeline[]::new);
    }

    @Test
    void 노출_조건을_통과한_규칙을_상관_순으로_고르고_제외_이유를_센다() {
        ExplorerSection section = RuleExplorer.analyze(ReportDataFixture.data(A, false, cohort()));

        assertThat(section.cohortSize()).isEqualTo(30);
        assertThat(section.retained()).isEqualTo(9);
        assertThat(section.top()).extracting(ranked -> ranked.rule().key()).containsExactly("room-1", "room-3");
        RankedRule first = section.top().getFirst();
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.days()).isEqualTo(3);
        assertThat(first.table().doers()).isEqualTo(10);
        assertThat(first.strength()).isEqualTo(Strength.STRONG);
        assertThat(section.passed()).isEqualTo(2);
        assertThat(section.ruleCount()).isEqualTo(40);
        assertThat(section.tally())
                .containsEntry(Exposure.NOT_COLLECTED, 13)
                .containsEntry(Exposure.TOO_FEW, 25);
        assertThat(section.fixedRuleExposure()).isEqualTo(Exposure.TOO_FEW);
    }

    @Test
    void Amplitude가_수집됐으면_앱_행동_규칙도_겨룬다() {
        ExplorerSection section = RuleExplorer.analyze(ReportDataFixture.data(A, true, cohort()));

        assertThat(section.top())
                .extracting(ranked -> ranked.rule().key())
                .containsExactly("room-1", "room-3", "simple-mode");
        assertThat(section.tally()).doesNotContainKey(Exposure.NOT_COLLECTED);
    }

    @Test
    void 연속_일수는_1위면_1위_연속_2위_이하면_Top_3_연속이다() {
        List<List<String>> previous = List.of(List.of("x", "y", "z"), List.of("y", "x"));

        assertThat(RuleExplorer.days("x", 1, previous)).isEqualTo(2);
        assertThat(RuleExplorer.days("y", 2, previous)).isEqualTo(3);
        assertThat(RuleExplorer.days("z", 3, previous)).isEqualTo(2);
        assertThat(RuleExplorer.days("w", 1, previous)).isEqualTo(1);
    }

    @Test
    void 코호트가_비면_통과한_규칙이_없다() {
        ExplorerSection section = RuleExplorer.analyze(ReportDataFixture.data(A, true));

        assertThat(section.top()).isEmpty();
        assertThat(section.passed()).isZero();
        assertThat(section.tally()).containsEntry(Exposure.TOO_FEW, 40);
    }
}
```

`src/test/java/project/study/metrics/report/analyzer/ActiveUserClassifierTest.java`:
```java
package project.study.metrics.report.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.ReportDataFixture;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;

class ActiveUserClassifierTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    private static UserTimeline[] users() {
        return new UserTimeline[] {
            user(1, A.minusDays(2))
                    .profile("공시생3년차", "CIVIL_SERVICE", "9급")
                    .session(A, 9, 60, 0, true)
                    .subject(0, null)
                    .amp(AmplitudeKeys.APP_LAUNCHED, 1, 1)
                    .amp(AmplitudeKeys.APP_LAUNCHED, 2, 1)
                    .build(),
            user(2, A).profile("아침형인간", "CSAT", null).session(A, 20, 160, 0, false).build(),
            user(3, A.minusDays(20)).studyOnDays(18, 19, 20).build(), // A−2, A−1, A
            user(4, A.minusDays(31)).studyOnDays(11, 31).build(), // A−20, A — 2주 쉬다 옴
            user(5, A.minusDays(10)).studyOnDays(10).build(), // 가입 뒤 처음 공부 — 돌아온 사람
            user(6, A.minusDays(30)).studyOnDays(20, 30).build(), // A−10, A — 가끔
            user(7, A.minusDays(30)).studyOnDays(29).build(), // 어제는 안 함
            user(8, A.minusDays(40)).ampOn(AmplitudeKeys.APP_LAUNCHED, A, 2).build() // 앱만 열었다
        };
    }

    @Test
    void 단계는_새로_온_사람이_먼저이고_나머지는_최근_공부_패턴으로_나눈다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, true, users()));

        assertThat(section.total()).isEqualTo(6);
        assertThat(section.count(Stage.NEW)).isEqualTo(2);
        assertThat(section.count(Stage.STEADY)).isEqualTo(1);
        assertThat(section.count(Stage.RETURNING)).isEqualTo(2);
        assertThat(section.count(Stage.OCCASIONAL)).isEqualTo(1);
        assertThat(section.byStage().get(Stage.NEW)).extracting(ActiveUserRow::userId).containsExactly(2L, 1L);
        assertThat(section.appOnly()).isEqualTo(1);
    }

    @Test
    void 한_줄_프로필에_필요한_값을_담는다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, true, users()));

        ActiveUserRow row = section.byStage().get(Stage.NEW).get(1);
        assertThat(row).isEqualTo(new ActiveUserRow(1, "공시생3년차", 2, 1, 3600, true, 1, "공무원(9급)", 2));
        assertThat(section.byStage().get(Stage.NEW).getFirst().daysSinceSignup()).isZero();
    }

    @Test
    void Amplitude가_없으면_앱_관련_값을_비운다() {
        ActiveUsersSection section = ActiveUserClassifier.analyze(ReportDataFixture.data(A, false, users()));

        assertThat(section.appOnly()).isNull();
        assertThat(section.byStage().get(Stage.NEW)).extracting(ActiveUserRow::appDays7).containsOnlyNulls();
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.analyzer.RuleExplorerTest" --tests "project.study.metrics.report.analyzer.ActiveUserClassifierTest"`
Expected: FAIL — `cannot find symbol: class RuleExplorer`

- [ ] **Step 3: 자동 탐색을 만든다**

`src/main/java/project/study/metrics/report/analyzer/ExplorerSection.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.Strength;
import project.study.metrics.report.rule.TwoByTwo;

/**
 * 🔍 자동 탐색 (스펙 §4.5).
 *
 * @param retained 코호트 중 둘째 주에도 공부한 사람
 * @param tally 규칙 40개의 판정별 개수
 * @param fixedRuleExposure 고정 규칙(첫 주 3일 이상)의 판정 — PASS가 아니면 꼬리에 빠진 이유를 쓴다
 */
public record ExplorerSection(
        LocalDate cohortFrom,
        LocalDate cohortTo,
        int cohortSize,
        int retained,
        List<RankedRule> top,
        Map<Exposure, Integer> tally,
        Exposure fixedRuleExposure) {

    /** @param days 오늘 포함 연속 일수 — 1위면 1위 연속, 2~3위면 Top 3 연속 (최대 3) */
    public record RankedRule(SegmentRule rule, TwoByTwo table, int rank, int days) {
        public boolean fixed() {
            return RuleCatalog.fixed(rule);
        }

        public Strength strength() {
            return Strength.of(table.phi());
        }
    }

    public int passed() {
        return tally.getOrDefault(Exposure.PASS, 0);
    }

    public int ruleCount() {
        return tally.values().stream().mapToInt(Integer::intValue).sum();
    }
}
```

`src/main/java/project/study/metrics/report/analyzer/RuleExplorer.java`:
```java
package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import project.study.metrics.report.Cohorts;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.CohortStats;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.TwoByTwo;

/**
 * 🔍 자동 탐색 (스펙 §4.5, §6) — 코호트 C에 규칙 40개를 적용해 노출 조건을 통과한 것을 phi 내림차순 Top 3로. 같은 계산을
 * A−1·A−2로도 돌려 연속 일수를 붙인다(우연히 높은 규칙을 거르는 두 번째 거름망).
 */
public final class RuleExplorer {

    static final int TOP = 3;
    static final int TREND_DAYS_BACK = 2;

    // phi 내림차순 → 한 사람 수 내림차순 → 카탈로그 순(안정 정렬)
    private static final Comparator<Result> RANKING = Comparator.comparingDouble((Result result) ->
                    result.table().phi())
            .reversed()
            .thenComparing(result -> result.table().doers(), Comparator.reverseOrder());

    private RuleExplorer() {}

    record Result(SegmentRule rule, TwoByTwo table, Exposure exposure) {}

    record Evaluation(int cohortSize, int retained, List<Result> results, List<Result> ranked) {
        List<String> topKeys() {
            return ranked.stream().limit(TOP).map(result -> result.rule().key()).toList();
        }
    }

    public static ExplorerSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        Evaluation today = evaluate(data, anchor);
        List<List<String>> previous = IntStream.rangeClosed(1, TREND_DAYS_BACK)
                .mapToObj(back -> evaluate(data, anchor.minusDays(back)).topKeys())
                .toList();
        List<RankedRule> top = new ArrayList<>();
        for (int index = 0; index < Math.min(TOP, today.ranked().size()); index++) {
            Result result = today.ranked().get(index);
            int rank = index + 1;
            top.add(new RankedRule(result.rule(), result.table(), rank, days(result.rule().key(), rank, previous)));
        }
        Map<Exposure, Integer> tally = new EnumMap<>(Exposure.class);
        today.results().forEach(result -> tally.merge(result.exposure(), 1, Integer::sum));
        Exposure fixed = today.results().stream()
                .filter(result -> RuleCatalog.fixed(result.rule()))
                .map(Result::exposure)
                .findFirst()
                .orElseThrow();
        return new ExplorerSection(
                Cohorts.weekTwoFrom(anchor),
                Cohorts.weekTwoTo(anchor),
                today.cohortSize(),
                today.retained(),
                top,
                tally,
                fixed);
    }

    static Evaluation evaluate(ReportData data, LocalDate anchor) {
        List<UserTimeline> cohort = Cohorts.weekTwo(data, anchor);
        CohortStats stats = CohortStats.of(cohort);
        boolean amplitude = Cohorts.firstWeeksCollected(data, anchor);
        List<Result> results = RuleCatalog.all().stream()
                .map(rule -> result(rule, cohort, stats, amplitude))
                .toList();
        List<Result> ranked = results.stream()
                .filter(result -> result.exposure() == Exposure.PASS)
                .sorted(RANKING)
                .toList();
        int retained = (int) cohort.stream().filter(Cohorts::retainedWeekTwo).count();
        return new Evaluation(cohort.size(), retained, results, ranked);
    }

    private static Result result(SegmentRule rule, List<UserTimeline> cohort, CohortStats stats, boolean amplitude) {
        if (rule.amplitude() && !amplitude) {
            return new Result(rule, null, Exposure.NOT_COLLECTED);
        }
        TwoByTwo table = TwoByTwo.of(cohort, user -> rule.matches(user, stats), Cohorts::retainedWeekTwo);
        return new Result(rule, table, table.exposure());
    }

    /** 오늘 포함 연속 일수. 1위면 그 전 날들도 1위였는지, 2~3위면 Top 3에 들었는지를 A−1부터 거꾸로 본다. */
    static int days(String key, int rank, List<List<String>> previousTops) {
        int days = 1;
        for (List<String> tops : previousTops) {
            boolean kept = rank == 1 ? !tops.isEmpty() && tops.getFirst().equals(key) : tops.contains(key);
            if (!kept) {
                break;
            }
            days++;
        }
        return days;
    }
}
```

- [ ] **Step 4: 어제 공부한 유저 분류를 만든다**

`src/main/java/project/study/metrics/report/analyzer/ActiveUsersSection.java`:
```java
package project.study.metrics.report.analyzer;

import java.util.List;
import java.util.Map;

/**
 * 🧑‍🎓 어제 공부한 유저 (스펙 §4.6).
 *
 * @param appOnly 어제 앱을 열었지만 공부한 날이 아닌 사람 수, 어제 Amplitude가 미수집이면 null
 */
public record ActiveUsersSection(int total, Map<Stage, List<ActiveUserRow>> byStage, Integer appOnly) {

    /** 선언 순서가 요약·스레드의 표시 순서다. 판정 순서는 ActiveUserClassifier.stage. */
    public enum Stage {
        NEW("새로 온 사람"),
        STEADY("꾸준한 사람"),
        RETURNING("돌아온 사람"),
        OCCASIONAL("가끔 오는 사람");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param streak A부터 거꾸로 센 연속 공부한 날
     * @param socialMostly 어제 순공의 절반 이상이 소셜 세션
     * @param appDays7 최근 7일 앱 실행 일수, Amplitude 미수집이면 null
     */
    public record ActiveUserRow(
            long userId,
            String nickname,
            long daysSinceSignup,
            int streak,
            int focusSec,
            boolean socialMostly,
            int liveSubjects,
            String profile,
            Integer appDays7) {}

    public int count(Stage stage) {
        return byStage.getOrDefault(stage, List.of()).size();
    }
}
```

`src/main/java/project/study/metrics/report/analyzer/ActiveUserClassifier.java`:
```java
package project.study.metrics.report.analyzer;

import static java.util.stream.Collectors.toSet;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import project.study.metrics.report.AmplitudeKeys;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Profiles;
import project.study.metrics.report.ReportData;
import project.study.metrics.report.UserTimeline;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.fact.SessionFact;

/** 🧑‍🎓 어제 공부한 유저를 단계로 나눈다 (스펙 §4.6). */
public final class ActiveUserClassifier {

    private static final int NEW_DAYS = 6;
    private static final int QUIET_DAYS = 14;
    private static final int RECENT_DAYS = 6;
    private static final int STEADY_STUDY_DAYS = 3;
    private static final Comparator<ActiveUserRow> ORDER = Comparator.comparingInt(ActiveUserRow::focusSec)
            .reversed()
            .thenComparingLong(ActiveUserRow::userId);

    private ActiveUserClassifier() {}

    public static ActiveUsersSection analyze(ReportData data) {
        LocalDate anchor = data.anchor();
        List<UserTimeline> active = data.studiedOn(anchor);
        boolean recentApp = data.amplitudeCollected(anchor.minusDays(RECENT_DAYS), anchor);
        Map<Stage, List<ActiveUserRow>> byStage = new EnumMap<>(Stage.class);
        for (UserTimeline user : active) {
            byStage.computeIfAbsent(stage(user, anchor), stage -> new ArrayList<>())
                    .add(row(user, anchor, recentApp));
        }
        byStage.values().forEach(rows -> rows.sort(ORDER));
        Set<Long> studied = active.stream().map(UserTimeline::userId).collect(toSet());
        Integer appOnly = data.amplitudeCollected(anchor, anchor)
                ? (int) data.appOpenedOnAnchor().stream().filter(id -> !studied.contains(id)).count()
                : null;
        return new ActiveUsersSection(active.size(), byStage, appOnly);
    }

    /** 위에서 먼저 맞는 하나 — 새로 온 사람 → 돌아온 사람 → 꾸준한 사람 → 가끔 오는 사람. */
    static Stage stage(UserTimeline user, LocalDate anchor) {
        if (!user.signupDate().isBefore(anchor.minusDays(NEW_DAYS))) {
            return Stage.NEW;
        }
        if (user.studyDays(anchor.minusDays(QUIET_DAYS), anchor.minusDays(1)) == 0) {
            return Stage.RETURNING;
        }
        if (user.studyDays(anchor.minusDays(RECENT_DAYS), anchor) >= STEADY_STUDY_DAYS) {
            return Stage.STEADY;
        }
        return Stage.OCCASIONAL;
    }

    private static ActiveUserRow row(UserTimeline user, LocalDate anchor, boolean recentApp) {
        int focus = user.focusSec(anchor);
        int social = user.sessions(anchor, anchor).stream()
                .filter(SessionFact::social)
                .mapToInt(SessionFact::focusSec)
                .sum();
        Integer appDays = recentApp
                ? user.amplitudeDays(AmplitudeKeys.APP_LAUNCHED, anchor.minusDays(RECENT_DAYS), anchor)
                : null;
        return new ActiveUserRow(
                user.userId(),
                Profiles.nickname(user),
                ChronoUnit.DAYS.between(user.signupDate(), anchor),
                user.streakEndingAt(anchor),
                focus,
                social * 2 >= focus,
                user.liveSubjectsAt(Kst.startOf(anchor.plusDays(1))),
                Profiles.label(user),
                appDays);
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.analyzer.RuleExplorerTest" --tests "project.study.metrics.report.analyzer.ActiveUserClassifierTest"`
Expected: PASS (7 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/metrics/report/analyzer src/test/java/project/study/metrics/report/analyzer
git commit -m "feat: 규칙 자동 탐색과 어제 공부한 유저 단계를 계산한다 (BY-904)"
```

---
### Task 8: 섹션 조립과 요약·가입·리텐션 렌더링

**Files:**
- Create: `src/main/java/project/study/metrics/report/SectionResult.java`, `DailyReport.java`
- Create: `src/main/java/project/study/metrics/report/render/Fmt.java`, `SummaryRenderer.java`, `SignupRenderer.java`, `RetentionRenderer.java`
- Test: `src/test/java/project/study/metrics/report/render/ReportSamples.java`(픽스처), `FmtTest.java`, `SummaryRendererTest.java`,
  `SignupRendererTest.java`, `RetentionRendererTest.java`, `src/test/java/project/study/metrics/report/DailyReportTest.java`

**Interfaces:**
- Consumes: Task 5·6·7 섹션 레코드와 분석기
- Produces:
  - `SectionResult<T>` — `of(T)`, `failed()`, `compute(String section, Supplier<T>)`(예외 → 로그·Sentry·failed), `value(): Optional<T>`
  - `record DailyReport(LocalDate anchor, SectionResult<SignupSection> signup, SectionResult<RetentionSection> retention,
    SectionResult<SegmentSection> segment, SectionResult<RosterSection> roster, SectionResult<ExplorerSection> explorer,
    SectionResult<ActiveUsersSection> active)` — `static analyze(ReportData)`
  - `Fmt`: `NONE`, `monthDay`, `monthDayWeekday`, `range`, `percent(Rate)`, `rate(Rate)`, `duration(int sec)`, `phi(double)`, `decimal(double)`,
    `bar(int, int)`, `bandDash(int)`, `width(String)`, `padRight`, `padLeft`, `streak(int)`, `code(List<String>)`, `trend(RankedRule)`
  - `SummaryRenderer.render(DailyReport): String`, `SignupRenderer.render(SignupSection): String`, `RetentionRenderer.render(RetentionSection): String`
  - 테스트 픽스처 `ReportSamples` — 부록 A 숫자의 섹션 6개와 `report(...)`

- [ ] **Step 1: 섹션 픽스처를 만든다**

`src/test/java/project/study/metrics/report/render/ReportSamples.java`:
```java
package project.study.metrics.report.render;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.Rate;
import project.study.metrics.report.SectionResult;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;
import project.study.metrics.report.analyzer.RosterCard;
import project.study.metrics.report.analyzer.RosterCard.Habits;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;
import project.study.metrics.report.analyzer.SignupSection.Share;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.RuleCatalog;
import project.study.metrics.report.rule.SegmentRule;
import project.study.metrics.report.rule.TwoByTwo;

/** 테스트용 — 스펙 부록 A 숫자로 채운 섹션들. 기준일 A = 2026-10-12(월). */
public final class ReportSamples {

    public static final LocalDate A = LocalDate.of(2026, 10, 12);
    public static final LocalDate COHORT_FROM = A.minusDays(40);
    public static final LocalDate COHORT_TO = A.minusDays(13);

    private ReportSamples() {}

    public static SegmentRule rule(String key) {
        return RuleCatalog.all().stream().filter(rule -> rule.key().equals(key)).findFirst().orElseThrow();
    }

    public static SignupSection signup() {
        List<HourBand> bands = List.of(
                new HourBand(0, 10, 1),
                new HourBand(3, 2, 0),
                new HourBand(6, 5, 2),
                new HourBand(9, 9, 1),
                new HourBand(12, 12, 1),
                new HourBand(15, 14, 2),
                new HourBand(18, 20, 3),
                new HourBand(21, 26, 5));
        List<Share> categories = List.of(
                new Share("수능", 30),
                new Share("공무원", 22),
                new Share("자격증", 14),
                new Share("취업", 8),
                new Share("어학", 5),
                new Share("전문직", 2),
                new Share("미입력", 17));
        Attribution attribution = new Attribution(
                98, 37, List.of(new Share("instagram", 24), new Share("tiktok", 9), new Share("naver", 3)), 4);
        return new SignupSection(A, 38, 503L, 540L, Rate.of(6, 38), 98, bands, categories, attribution);
    }

    public static RetentionSection retention() {
        return new RetentionSection(
                List.of(
                        new RetentionRow(Window.NEXT_DAY, Rate.of(27, 268), Rate.of(30, 270), A.minusDays(28), A.minusDays(1)),
                        new RetentionRow(Window.WEEK_TWO, Rate.of(16, 252), Rate.of(12, 240), COHORT_FROM, COHORT_TO),
                        new RetentionRow(Window.WEEK_FIVE, Rate.of(6, 190), Rate.of(6, 200), A.minusDays(61), A.minusDays(34))),
                Rate.of(199, 265),
                A.minusDays(33),
                A.minusDays(6));
    }

    public static SegmentSection segment() {
        List<BehaviorRow> behaviors = List.of(
                new BehaviorRow("소셜 방 3번 이상 참여", Rate.of(8, 14), Rate.of(2, 238)),
                new BehaviorRow("D-day 설정", Rate.of(7, 14), Rate.of(23, 238)));
        List<Comparison> who = List.of(
                new Comparison(
                        "분야",
                        List.of(
                                new ShareRow("공무원", Rate.of(5, 14), Rate.of(50, 238)),
                                new ShareRow("미입력", Rate.of(1, 14), Rate.of(69, 238)))),
                new Comparison("광고 유입", List.of(new ShareRow("", Rate.of(4, 14), Rate.of(98, 238)))));
        return new SegmentSection(
                COHORT_FROM,
                COHORT_TO,
                252,
                List.of(
                        new GroupRow("3일 이상", 14, Rate.of(8, 14)),
                        new GroupRow("1~2일", 46, Rate.of(6, 46)),
                        new GroupRow("0일", 192, Rate.of(2, 192))),
                new TwoByTwo(8, 6, 8, 230),
                true,
                true,
                behaviors,
                new BehaviorRow("프로필(분야·목표) 입력", Rate.of(13, 14), Rate.of(176, 238)),
                who);
    }

    public static RosterCard bearCard() {
        Instant signup = Instant.parse("2026-09-29T13:00:00Z"); // 9/29(화) 22시 KST
        return new RosterCard(
                true,
                418,
                "공부하는곰",
                signup,
                "공무원(7급 합격)",
                "iOS",
                "유입: 광고(instagram)",
                new StudyFlow(5, 11 * 3600 + 20 * 60, 4, 4, 8 * 3600 + 5 * 60, A, false, 3),
                List.of("소셜 방 7회", "과목 4개", "할 일 완료 12개", "D-day", "간단 모드", "가로 화면", "백색소음", "랭킹", "앱 실행 6일"),
                false,
                new Habits(OptionalDouble.of(48 * 60), OptionalInt.of(21), OptionalInt.of(62), OptionalDouble.of(0.5),
                        OptionalDouble.of(1.2)));
    }

    public static RosterCard leftCard() {
        Instant signup = Instant.parse("2026-09-29T04:00:00Z"); // 9/29(화) 13시 KST
        return new RosterCard(
                true,
                421,
                "자격증따자",
                signup,
                "자격증",
                "Android",
                "유입: 표시 없음",
                new StudyFlow(3, 110 * 60, 3, 0, 0, LocalDate.of(2026, 10, 5), true, 0),
                List.of("과목 1개", "앱 실행 3일"),
                false,
                new Habits(OptionalDouble.of(18 * 60), OptionalInt.of(12), OptionalInt.of(0), OptionalDouble.of(2.4),
                        OptionalDouble.of(6.5)));
    }

    public static RosterSection roster() {
        return new RosterSection(
                A,
                COHORT_FROM,
                COHORT_TO,
                new RosterGroup(1, List.of(bearCard()), null),
                new RosterGroup(1, List.of(leftCard()), null));
    }

    public static ExplorerSection explorer() {
        Map<Exposure, Integer> tally = new EnumMap<>(Exposure.class);
        tally.put(Exposure.PASS, 6);
        tally.put(Exposure.TOO_FEW, 14);
        tally.put(Exposure.TOO_COMMON, 2);
        tally.put(Exposure.WEAK, 15);
        tally.put(Exposure.NOT_COLLECTED, 3);
        return new ExplorerSection(
                COHORT_FROM,
                COHORT_TO,
                252,
                16,
                List.of(
                        new RankedRule(rule("room-3"), new TwoByTwo(7, 3, 9, 233), 1, 3),
                        new RankedRule(rule(RuleCatalog.FIXED_RULE_KEY), new TwoByTwo(8, 6, 8, 230), 2, 3)),
                tally,
                Exposure.PASS);
    }

    public static ActiveUserRow morningRow() {
        return new ActiveUserRow(531, "아침형인간", 0, 1, 160 * 60, false, 2, "수능", 1);
    }

    public static ActiveUsersSection active() {
        Map<Stage, List<ActiveUserRow>> byStage = new EnumMap<>(Stage.class);
        byStage.put(Stage.NEW, rows(morningRow(), 6));
        byStage.put(Stage.STEADY, rows(new ActiveUserRow(418, "공부하는곰", 13, 3, 210 * 60, true, 4, "공무원(7급 합격)", 6), 5));
        byStage.put(Stage.RETURNING, rows(new ActiveUserRow(212, "다시시작", 31, 1, 50 * 60, false, 1, "어학(토익 900)", 2), 2));
        byStage.put(Stage.OCCASIONAL, rows(new ActiveUserRow(300, "가끔이", 20, 1, 20 * 60, false, 1, "분야 미입력", 2), 2));
        return new ActiveUsersSection(15, byStage, 9);
    }

    private static List<ActiveUserRow> rows(ActiveUserRow first, int count) {
        List<ActiveUserRow> rows = new ArrayList<>();
        rows.add(first);
        for (int i = 1; i < count; i++) {
            rows.add(new ActiveUserRow(first.userId() + i, first.nickname() + i, first.daysSinceSignup(), 1, 600, false, 1, "수능", null));
        }
        return rows;
    }

    public static DailyReport report() {
        return new DailyReport(
                A,
                SectionResult.of(signup()),
                SectionResult.of(retention()),
                SectionResult.of(segment()),
                SectionResult.of(roster()),
                SectionResult.of(explorer()),
                SectionResult.of(active()));
    }
}
```

- [ ] **Step 2: 렌더링·조립 테스트를 쓴다**

`src/test/java/project/study/metrics/report/render/FmtTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;

class FmtTest {

    @Test
    void 날짜는_월_일_요일로_쓴다() {
        assertThat(Fmt.monthDayWeekday(LocalDate.of(2026, 10, 12))).isEqualTo("10/12(월)");
        assertThat(Fmt.range(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 29))).isEqualTo("9/2~9/29");
    }

    @Test
    void 비율은_분모가_0이면_줄표다() {
        assertThat(Fmt.rate(Rate.of(8, 14))).isEqualTo("57% (8/14)");
        assertThat(Fmt.percent(Rate.of(8, 14))).isEqualTo("57%");
        assertThat(Fmt.rate(Rate.of(0, 0))).isEqualTo("—");
        assertThat(Fmt.percent(Rate.of(0, 0))).isEqualTo("—");
    }

    @Test
    void 시간은_시간과_분으로_쓴다() {
        assertThat(Fmt.duration(11 * 3600 + 20 * 60)).isEqualTo("11시간 20분");
        assertThat(Fmt.duration(3 * 3600)).isEqualTo("3시간");
        assertThat(Fmt.duration(50 * 60 + 59)).isEqualTo("50분");
        assertThat(Fmt.duration(0)).isEqualTo("0분");
    }

    @Test
    void 막대는_10칸이고_0이_아니면_한_칸은_채운다() {
        assertThat(Fmt.bar(26, 26)).isEqualTo("██████████");
        assertThat(Fmt.bar(10, 26)).isEqualTo("████░░░░░░");
        assertThat(Fmt.bar(2, 26)).isEqualTo("█░░░░░░░░░");
        assertThat(Fmt.bar(0, 26)).isEqualTo("░░░░░░░░░░");
        assertThat(Fmt.bar(0, 0)).isEqualTo("░░░░░░░░░░");
    }

    @Test
    void 한글은_두_칸으로_세어_맞춘다() {
        assertThat(Fmt.width("가입 다음 날")).isEqualTo(12);
        assertThat(Fmt.padRight("0일", 6)).isEqualTo("0일   ");
        assertThat(Fmt.padLeft("5%", 4)).isEqualTo("  5%");
    }

    @Test
    void 연속은_60일을_넘으면_줄여_쓴다() {
        assertThat(Fmt.streak(3)).isEqualTo("3일");
        assertThat(Fmt.streak(61)).isEqualTo("60일+");
    }
}
```

`src/test/java/project/study/metrics/report/render/SummaryRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

class SummaryRendererTest {

    @Test
    void 요약은_용어_없이_풀어_쓴_다섯_줄이다() {
        assertThat(SummaryRenderer.render(ReportSamples.report()))
                .isEqualTo("""
                        📊 10/12(월) 일일 지표
                        • 가입 38명 · 가장 많이 가입한 시간 21~24시
                        • 다시 와서 공부한 비율: 가입 다음 날 10% · 7~13일 뒤 6% · 28~34일 뒤 3%  (지난주 11% · 5% · 3%)
                        • 첫 주에 3일 이상 공부한 14명 중 8명(57%)이 둘째 주에도 공부 — 1~2일 공부한 사람은 13%, 안 한 사람은 1% (명단은 스레드)
                        • 남는 사람과 가장 관련 깊은 행동: 첫 주에 소셜 방 3번 이상 참여 (연관 강함, 3일째 1위)
                        • 어제 공부한 15명: 새로 온 사람 6 (가입 첫 주) · 꾸준한 사람 5 (최근 7일 중 3일+) · 돌아온 사람 2 (2주 쉬다 옴) · 가끔 오는 사람 2 (최근 7일 중 1~2일)""");
    }

    @Test
    void 계산에_실패한_섹션은_그_줄만_계산_실패로_쓴다() {
        DailyReport base = ReportSamples.report();
        DailyReport report = new DailyReport(
                base.anchor(),
                base.signup(),
                SectionResult.failed(),
                base.segment(),
                base.roster(),
                base.explorer(),
                base.active());

        assertThat(SummaryRenderer.render(report))
                .contains("• 다시 와서 공부한 비율: 계산 실패")
                .contains("• 가입 38명");
    }
}
```

`src/test/java/project/study/metrics/report/render/SignupRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;

class SignupRendererTest {

    @Test
    void 가입_스레드는_회원번호_시간대_막대_분야_유입을_쓴다() {
        String text = SignupRenderer.render(ReportSamples.signup());

        assertThat(text.lines())
                .contains(
                        "🕐 가입",
                        "어제 가입 38명 (#503~#540)",
                        "가입하고 3시간 안에 공부한 사람: 16% (6/38)",
                        "최근 7일(10/6~10/12) 가입 98명 — 시간대별 가입 · 그중 3시간 안에 공부",
                        "00–03  ████░░░░░░  10   바로 공부 1/10 (10%)",
                        "03–06  █░░░░░░░░░   2   바로 공부 0/2",
                        "21–24  ██████████  26   바로 공부 5/26 (19%)",
                        "→ 가입이 가장 많은 시간 21~24시 · 바로 공부 비율이 가장 높은 시간 06~09시 (2/5)",
                        "공부 분야 (최근 7일 가입): 수능 31% · 공무원 22% · 자격증 14% · 취업 8% · 어학 5% · 전문직 2% · 미입력 17%",
                        "유입 (최근 7일 가입, Amplitude 기준): 광고 클릭 38% (37/98) · UTM 상위 instagram 24 · tiktok 9 · naver 3 · 미수집 4명");
        assertThat(text).contains("```\n00–03");
    }

    @Test
    void 가입이_없으면_막대와_분야를_쓰지_않는다() {
        List<HourBand> empty = List.of(
                new HourBand(0, 0, 0), new HourBand(3, 0, 0), new HourBand(6, 0, 0), new HourBand(9, 0, 0),
                new HourBand(12, 0, 0), new HourBand(15, 0, 0), new HourBand(18, 0, 0), new HourBand(21, 0, 0));
        SignupSection section = new SignupSection(
                ReportSamples.A, 0, null, null, Rate.of(0, 0), 0, empty, List.of(), new Attribution(0, 0, List.of(), 0));

        assertThat(SignupRenderer.render(section))
                .isEqualTo("""
                        🕐 가입
                        어제 가입 0명

                        최근 7일(10/6~10/12) 가입 0명""");
    }

    @Test
    void Amplitude_속성이_하나도_없으면_유입은_미수집만_쓴다() {
        SignupSection base = ReportSamples.signup();
        SignupSection section = new SignupSection(
                base.anchor(), base.yesterdayCount(), base.minUserId(), base.maxUserId(), base.yesterdayQuick(),
                base.weekTotal(), base.bands(), base.categories(), new Attribution(98, 0, List.of(), 98));

        assertThat(SignupRenderer.render(section)).contains("유입 (최근 7일 가입, Amplitude 기준): 미수집 98명");
    }
}
```

`src/test/java/project/study/metrics/report/render/RetentionRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetentionRendererTest {

    @Test
    void 리텐션_스레드는_구간_정의와_지난주_값을_쓴다() {
        String text = RetentionRenderer.render(ReportSamples.retention());

        assertThat(text.lines())
                .contains(
                        "📈 다시 와서 공부한 비율",
                        "공부 = 그날 10분 이상 공부한 날 · 구간이 다 끝난 최근 28일치 가입자만 셈",
                        "가입 다음 날  10% (27/268)   지난주  11%   [가입 9/14~10/11]",
                        "→ 7~13일 뒤 = 가입 7일 뒤부터 13일 뒤까지 7일 중 하루라도 공부한 사람.",
                        "  6일 뒤·14일 뒤에만 온 사람은 안 셈 (28~34일 뒤도 같음)",
                        "첫 주에 한 번도 공부 안 한 사람: 75% (199/265) [가입 9/9~10/6]");
        assertThat(text).contains("[가입 9/2~9/29]").contains("[가입 8/12~9/8]");
    }
}
```

`src/test/java/project/study/metrics/report/DailyReportTest.java`:
```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.metrics.report.TimelineBuilder.user;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyReportTest {

    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    @Test
    void 섹션_여섯_개를_모두_계산한다() {
        DailyReport report = DailyReport.analyze(ReportDataFixture.data(A, true, user(1, A).studyOnDay(0, 30).build()));

        assertThat(report.anchor()).isEqualTo(A);
        assertThat(report.signup().value()).isPresent();
        assertThat(report.retention().value()).isPresent();
        assertThat(report.segment().value()).isPresent();
        assertThat(report.roster().value()).isPresent();
        assertThat(report.explorer().value()).isPresent();
        assertThat(report.active().value()).hasValueSatisfying(active -> assertThat(active.total()).isEqualTo(1));
    }

    @Test
    void 한_섹션이_예외를_던지면_그_섹션만_실패로_남는다() {
        SectionResult<String> failed = SectionResult.compute("테스트", () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(failed.value()).isEmpty();
        assertThat(SectionResult.compute("테스트", () -> "ok").value()).contains("ok");
    }
}
```

- [ ] **Step 3: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.render.*" --tests "project.study.metrics.report.DailyReportTest"`
Expected: FAIL — `cannot find symbol: class DailyReport`

- [ ] **Step 4: 섹션 조립을 만든다**

`src/main/java/project/study/metrics/report/SectionResult.java`:
```java
package project.study.metrics.report;

import io.sentry.Sentry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** 섹션 하나의 계산 결과. 한 섹션이 실패해도 나머지는 보낸다 — 실패한 섹션은 "계산 실패"로 쓰고 Sentry에 남긴다 (스펙 §10). */
@Slf4j
public final class SectionResult<T> {

    private final T value;

    private SectionResult(T value) {
        this.value = value;
    }

    public static <T> SectionResult<T> of(T value) {
        return new SectionResult<>(Objects.requireNonNull(value));
    }

    public static <T> SectionResult<T> failed() {
        return new SectionResult<>(null);
    }

    public static <T> SectionResult<T> compute(String section, Supplier<T> supplier) {
        try {
            return of(supplier.get());
        } catch (RuntimeException e) {
            log.error("일일 리포트 섹션 계산 실패 (섹션={})", section, e);
            Sentry.captureException(e);
            return failed();
        }
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }
}
```

`src/main/java/project/study/metrics/report/DailyReport.java`:
```java
package project.study.metrics.report;

import java.time.LocalDate;
import project.study.metrics.report.analyzer.ActiveUserClassifier;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.RetentionAnalyzer;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RuleExplorer;
import project.study.metrics.report.analyzer.SegmentAnalyzer;
import project.study.metrics.report.analyzer.SegmentRoster;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SignupAnalyzer;
import project.study.metrics.report.analyzer.SignupSection;

/** 리포트 한 번의 섹션 여섯 개 — 스레드 순서와 같다 (스펙 §2 결정 8). */
public record DailyReport(
        LocalDate anchor,
        SectionResult<SignupSection> signup,
        SectionResult<RetentionSection> retention,
        SectionResult<SegmentSection> segment,
        SectionResult<RosterSection> roster,
        SectionResult<ExplorerSection> explorer,
        SectionResult<ActiveUsersSection> active) {

    public static DailyReport analyze(ReportData data) {
        return new DailyReport(
                data.anchor(),
                SectionResult.compute("가입", () -> SignupAnalyzer.analyze(data)),
                SectionResult.compute("리텐션", () -> RetentionAnalyzer.analyze(data)),
                SectionResult.compute("첫 주 비교", () -> SegmentAnalyzer.analyze(data)),
                SectionResult.compute("명단", () -> SegmentRoster.analyze(data)),
                SectionResult.compute("자동 탐색", () -> RuleExplorer.analyze(data)),
                SectionResult.compute("어제 공부한 유저", () -> ActiveUserClassifier.analyze(data)));
    }
}
```

- [ ] **Step 5: 포맷 도구와 렌더러 셋을 만든다**

`src/main/java/project/study/metrics/report/render/Fmt.java`:
```java
package project.study.metrics.report.render;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;

/** 메시지 숫자·날짜 표기. 표·막대는 코드 블록 안에서 한글을 두 칸으로 세어 맞춘다. */
public final class Fmt {

    public static final String NONE = "—";

    private static final int BAR_WIDTH = 10;
    private static final int MAX_STREAK = 60;
    // 한글 자모·한글 호환 자모·한자·한글 음절·전각 — 고정폭 글꼴에서 두 칸
    private static final int[][] WIDE_RANGES = {
        {0x1100, 0x115F}, {0x3130, 0x318F}, {0x4E00, 0x9FFF}, {0xAC00, 0xD7A3}, {0xFF01, 0xFF60}
    };

    private Fmt() {}

    public static String monthDay(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    public static String monthDayWeekday(LocalDate date) {
        return monthDay(date) + "(" + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")";
    }

    public static String range(LocalDate from, LocalDate to) {
        return monthDay(from) + "~" + monthDay(to);
    }

    public static String percent(Rate rate) {
        return rate.defined() ? rate.percent() + "%" : NONE;
    }

    public static String rate(Rate rate) {
        return rate.defined()
                ? "%d%% (%d/%d)".formatted(rate.percent(), rate.numerator(), rate.denominator())
                : NONE;
    }

    public static String duration(int seconds) {
        int minutes = seconds / 60;
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "분";
        }
        return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
    }

    public static String phi(double phi) {
        return String.format(Locale.ROOT, "%.2f", phi);
    }

    public static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 10칸 막대. 값이 0이 아니면 반올림이 0이어도 한 칸은 채운다. */
    public static String bar(int value, int max) {
        int filled = max == 0 ? 0 : (int) Math.round((double) BAR_WIDTH * value / max);
        if (value > 0 && filled == 0) {
            filled = 1;
        }
        return "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
    }

    /** 막대 라벨 — 21 → "21–24". */
    public static String bandDash(int startHour) {
        return "%02d–%02d".formatted(startHour, startHour + 3);
    }

    public static String streak(int days) {
        return days > MAX_STREAK ? MAX_STREAK + "일+" : days + "일";
    }

    /** 고정폭 글꼴에서 한글·한자·전각은 두 칸이다. */
    public static int width(String text) {
        return text.codePoints().map(Fmt::cellWidth).sum();
    }

    public static String padRight(String text, int width) {
        return text + " ".repeat(Math.max(0, width - width(text)));
    }

    public static String padLeft(String text, int width) {
        return " ".repeat(Math.max(0, width - width(text))) + text;
    }

    public static String code(List<String> lines) {
        return "```\n" + String.join("\n", lines) + "\n```";
    }

    /** 1위면 "3일째 1위"·"오늘 처음 1위", 2~3위면 "3일째 상위권"·"오늘 처음 상위권". */
    public static String trend(RankedRule rule) {
        String place = rule.rank() == 1 ? "1위" : "상위권";
        return rule.days() > 1 ? rule.days() + "일째 " + place : "오늘 처음 " + place;
    }

    private static int cellWidth(int codePoint) {
        for (int[] range : WIDE_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return 2;
            }
        }
        return 1;
    }
}
```

`src/main/java/project/study/metrics/report/render/SummaryRenderer.java`:
```java
package project.study.metrics.report.render;

import io.sentry.Sentry;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.Kst;
import project.study.metrics.report.SectionResult;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SignupSection;

/**
 * 📊 본 메시지(요약) — 매일 훑어보는 줄이라 용어 없이 풀어 쓴다(스펙 §4.7). 정의·분모·코호트 기간은 스레드에 둔다.
 */
@Slf4j
public final class SummaryRenderer {

    private SummaryRenderer() {}

    public static String render(DailyReport report) {
        return String.join(
                "\n",
                List.of(
                        "📊 " + Fmt.monthDayWeekday(report.anchor()) + " 일일 지표",
                        line(report.signup(), "가입", SummaryRenderer::signup),
                        line(report.retention(), "다시 와서 공부한 비율", SummaryRenderer::retention),
                        line(report.segment(), "첫 주 3일 이상 공부한 사람", SummaryRenderer::segment),
                        line(report.explorer(), "남는 사람과 관련 깊은 행동", SummaryRenderer::explorer),
                        line(report.active(), "어제 공부한 사람", SummaryRenderer::active)));
    }

    private static <T> String line(SectionResult<T> result, String label, Function<T, String> renderer) {
        String failed = "• " + label + ": 계산 실패";
        try {
            return result.value().map(value -> "• " + renderer.apply(value)).orElse(failed);
        } catch (RuntimeException e) {
            log.error("일일 리포트 요약 줄 렌더링 실패 (줄={})", label, e);
            Sentry.captureException(e);
            return failed;
        }
    }

    private static String signup(SignupSection section) {
        String count = "가입 %d명".formatted(section.yesterdayCount());
        return section.peak()
                .map(band -> count + " · 가장 많이 가입한 시간 " + Kst.bandLabel(band.startHour()))
                .orElse(count);
    }

    private static String retention(RetentionSection section) {
        var rows = section.rows();
        return "다시 와서 공부한 비율: 가입 다음 날 %s · 7~13일 뒤 %s · 28~34일 뒤 %s  (지난주 %s · %s · %s)"
                .formatted(
                        Fmt.percent(rows.get(0).current()),
                        Fmt.percent(rows.get(1).current()),
                        Fmt.percent(rows.get(2).current()),
                        Fmt.percent(rows.get(0).lastWeek()),
                        Fmt.percent(rows.get(1).lastWeek()),
                        Fmt.percent(rows.get(2).lastWeek()));
    }

    private static String segment(SegmentSection section) {
        GroupRow three = section.groups().get(0);
        if (three.size() == 0) {
            return "첫 주에 3일 이상 공부한 사람 없음 (가입 %s %d명 중)"
                    .formatted(Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize());
        }
        return "첫 주에 3일 이상 공부한 %d명 중 %d명(%s)이 둘째 주에도 공부 — 1~2일 공부한 사람은 %s, 안 한 사람은 %s (명단은 스레드)"
                .formatted(
                        three.size(),
                        three.retained().numerator(),
                        Fmt.percent(three.retained()),
                        Fmt.percent(section.groups().get(1).retained()),
                        Fmt.percent(section.groups().get(2).retained()));
    }

    private static String explorer(ExplorerSection section) {
        if (section.top().isEmpty()) {
            return "남는 사람과 관련 깊은 행동: 오늘은 조건을 통과한 규칙 없음 (표본 부족)";
        }
        RankedRule first = section.top().getFirst();
        return "남는 사람과 가장 관련 깊은 행동: %s%s (연관 %s, %s)"
                .formatted(
                        first.rule().name(),
                        first.fixed() ? " (고정 세그먼트)" : "",
                        first.strength().label(),
                        Fmt.trend(first));
    }

    private static String active(ActiveUsersSection section) {
        if (section.total() == 0) {
            return "어제 공부한 사람 없음";
        }
        return ("어제 공부한 %d명: 새로 온 사람 %d (가입 첫 주) · 꾸준한 사람 %d (최근 7일 중 3일+) · 돌아온 사람 %d (2주 쉬다 옴)"
                        + " · 가끔 오는 사람 %d (최근 7일 중 1~2일)")
                .formatted(
                        section.total(),
                        section.count(Stage.NEW),
                        section.count(Stage.STEADY),
                        section.count(Stage.RETURNING),
                        section.count(Stage.OCCASIONAL));
    }
}
```

`src/main/java/project/study/metrics/report/render/SignupRenderer.java`:
```java
package project.study.metrics.report.render;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Kst;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SignupSection;
import project.study.metrics.report.analyzer.SignupSection.Attribution;
import project.study.metrics.report.analyzer.SignupSection.HourBand;

/** 🕐 가입 스레드 (부록 A 스레드 1). */
public final class SignupRenderer {

    private SignupRenderer() {}

    public static String render(SignupSection section) {
        List<String> lines = new ArrayList<>();
        lines.add("🕐 가입");
        if (section.yesterdayCount() == 0) {
            lines.add("어제 가입 0명");
        } else {
            lines.add("어제 가입 %d명 (#%d~#%d)"
                    .formatted(section.yesterdayCount(), section.minUserId(), section.maxUserId()));
            lines.add("가입하고 3시간 안에 공부한 사람: " + Fmt.rate(section.yesterdayQuick()));
        }
        lines.add("");
        String week = "최근 7일(%s) 가입 %d명".formatted(Fmt.range(section.weekFrom(), section.anchor()), section.weekTotal());
        if (section.weekTotal() == 0) {
            lines.add(week);
            return String.join("\n", lines);
        }
        lines.add(week + " — 시간대별 가입 · 그중 3시간 안에 공부");
        lines.add(Fmt.code(bars(section)));
        lines.add(peaks(section));
        lines.add("");
        lines.add("공부 분야 (최근 7일 가입): " + categories(section));
        lines.add("유입 (최근 7일 가입, Amplitude 기준): " + attribution(section.attribution()));
        return String.join("\n", lines);
    }

    private static List<String> bars(SignupSection section) {
        int max = section.bands().stream().mapToInt(HourBand::signups).max().orElse(0);
        return section.bands().stream()
                .map(band -> "%s  %s %3d%s"
                        .formatted(
                                Fmt.bandDash(band.startHour()), Fmt.bar(band.signups(), max), band.signups(), quick(band)))
                .toList();
    }

    // 5명 미만 구간은 비율이 흔들려 분수만 쓴다
    private static String quick(HourBand band) {
        if (band.signups() == 0) {
            return "";
        }
        String fraction = band.quick() + "/" + band.signups();
        return band.signups() >= SignupSection.MIN_SIGNUPS_FOR_RATE
                ? "   바로 공부 %s (%d%%)".formatted(fraction, band.quickRate().percent())
                : "   바로 공부 " + fraction;
    }

    private static String peaks(SignupSection section) {
        String peak = "→ 가입이 가장 많은 시간 "
                + Kst.bandLabel(section.peak().orElseThrow().startHour());
        return section.bestQuick()
                .map(best -> peak + " · 바로 공부 비율이 가장 높은 시간 %s (%d/%d)"
                        .formatted(Kst.bandLabel(best.startHour()), best.quick(), best.signups()))
                .orElse(peak);
    }

    private static String categories(SignupSection section) {
        return section.categories().stream()
                .map(share -> share.label() + " " + Fmt.percent(Rate.of(share.count(), section.weekTotal())))
                .collect(joining(" · "));
    }

    private static String attribution(Attribution attribution) {
        if (attribution.missing() == attribution.total()) {
            return "미수집 %d명".formatted(attribution.missing());
        }
        List<String> parts = new ArrayList<>();
        parts.add("광고 클릭 " + Fmt.rate(Rate.of(attribution.adClicks(), attribution.total())));
        if (!attribution.topUtm().isEmpty()) {
            parts.add("UTM 상위 "
                    + attribution.topUtm().stream()
                            .map(share -> share.label() + " " + share.count())
                            .collect(joining(" · ")));
        }
        if (attribution.missing() > 0) {
            parts.add("미수집 %d명".formatted(attribution.missing()));
        }
        return String.join(" · ", parts);
    }
}
```

`src/main/java/project/study/metrics/report/render/RetentionRenderer.java`:
```java
package project.study.metrics.report.render;

import project.study.metrics.report.analyzer.RetentionAnalyzer.Window;
import project.study.metrics.report.analyzer.RetentionSection;
import project.study.metrics.report.analyzer.RetentionSection.RetentionRow;

/** 📈 리텐션 스레드 (부록 A 스레드 2). */
public final class RetentionRenderer {

    private static final int LABEL_WIDTH = 13;
    private static final int PERCENT_WIDTH = 4;
    private static final int FRACTION_WIDTH = 10;

    private RetentionRenderer() {}

    public static String render(RetentionSection section) {
        return String.join(
                "\n",
                "📈 다시 와서 공부한 비율",
                "공부 = 그날 10분 이상 공부한 날 · 구간이 다 끝난 최근 28일치 가입자만 셈",
                Fmt.code(section.rows().stream().map(RetentionRenderer::row).toList()),
                "→ 7~13일 뒤 = 가입 7일 뒤부터 13일 뒤까지 7일 중 하루라도 공부한 사람.",
                "  6일 뒤·14일 뒤에만 온 사람은 안 셈 (28~34일 뒤도 같음)",
                "",
                "첫 주에 한 번도 공부 안 한 사람: %s [가입 %s]"
                        .formatted(
                                Fmt.rate(section.firstWeekNone()), Fmt.range(section.noneFrom(), section.noneTo())));
    }

    private static String row(RetentionRow row) {
        String fraction = row.current().defined()
                ? "(%d/%d)".formatted(row.current().numerator(), row.current().denominator())
                : "";
        return Fmt.padRight(label(row.window()), LABEL_WIDTH)
                + Fmt.padLeft(Fmt.percent(row.current()), PERCENT_WIDTH)
                + " "
                + Fmt.padRight(fraction, FRACTION_WIDTH)
                + " 지난주 "
                + Fmt.padLeft(Fmt.percent(row.lastWeek()), PERCENT_WIDTH)
                + "   [가입 "
                + Fmt.range(row.cohortFrom(), row.cohortTo())
                + "]";
    }

    private static String label(Window window) {
        return switch (window) {
            case NEXT_DAY -> "가입 다음 날";
            case WEEK_TWO -> "7~13일 뒤";
            case WEEK_FIVE -> "28~34일 뒤";
        };
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.render.*" --tests "project.study.metrics.report.DailyReportTest"`
Expected: PASS (14 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/project/study/metrics/report src/test/java/project/study/metrics/report
git commit -m "feat: 리포트 요약과 가입·리텐션 스레드를 렌더링한다 (BY-904)"
```

---
### Task 9: 나머지 스레드 렌더링과 메시지 조립

**Files:**
- Create: `src/main/java/project/study/metrics/report/render/SegmentRenderer.java`, `RosterRenderer.java`, `ExplorerRenderer.java`,
  `ActiveUsersRenderer.java`, `ReportRenderer.java`, `RenderedReport.java`
- Test: `src/test/java/project/study/metrics/report/render/SegmentRendererTest.java`, `RosterRendererTest.java`, `ExplorerRendererTest.java`,
  `ActiveUsersRendererTest.java`, `ReportRendererTest.java`

**Interfaces:**
- Consumes: Task 8 `Fmt`·`SummaryRenderer`·`SignupRenderer`·`RetentionRenderer`·`DailyReport`·`ReportSamples`, Task 6·7 섹션
- Produces:
  - `SegmentRenderer.render(SegmentSection)`, `RosterRenderer.render(RosterSection)`, `ExplorerRenderer.render(ExplorerSection)`,
    `ActiveUsersRenderer.render(ActiveUsersSection)` — 모두 `String`
  - `record RenderedReport(String summary, List<String> threads)` (threads 6개, 순서: 가입 → 리텐션 → 첫 주 비교 → 명단 → 탐색 → 어제 공부)
  - `ReportRenderer.render(DailyReport): RenderedReport`, `ReportRenderer.MAX_CHARS = 40_000`, `ReportRenderer.cap(String)`

- [ ] **Step 1: 테스트를 쓴다**

`src/test/java/project/study/metrics/report/render/SegmentRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.rule.TwoByTwo;

class SegmentRendererTest {

    @Test
    void 세_묶음_비교와_주로_한_행동_어떤_사람인가를_쓴다() {
        List<String> lines = SegmentRenderer.render(ReportSamples.segment()).lines().toList();

        assertThat(lines)
                .contains(
                        "👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나",
                        "가입 9/2~9/29 252명 · 첫 주 = 가입일 포함 7일 · 둘째 주 = 가입 7~13일 뒤",
                        "→ 3일 이상 vs 나머지: 57% vs 3% (+54%p) · 연관 강함 (0.51)",
                        "3일 이상인 사람이 첫 주에 주로 한 행동 (3일 이상 14명 / 나머지 238명)",
                        "어떤 사람인가 (3일 이상 / 나머지)",
                        "• 분야: 공무원 36% / 21% · 미입력 7% / 29%",
                        "• 광고 유입: 29% / 41%");
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("3일 이상 ").endsWith("14명 (6%)     57% (8/14)"));
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("1. 소셜 방 3번 이상 참여 ").endsWith(" 57% / 1%"));
        assertThat(lines)
                .anySatisfy(line -> assertThat(line)
                        .startsWith("3. 프로필(분야·목표) 입력 ")
                        .endsWith("93% / 74%   ※ 지금 기준 — 입력 시점은 기록이 없음"));
    }

    @Test
    void 첫_주_3일_이상이_5명_미만이면_비교를_생략한다고_쓴다() {
        SegmentSection base = ReportSamples.segment();
        SegmentSection section = new SegmentSection(
                base.cohortFrom(), base.cohortTo(), 20, base.groups(), new TwoByTwo(2, 1, 1, 16), false, true,
                List.of(), base.profile(), List.of());

        assertThat(SegmentRenderer.render(section))
                .contains("3일 이상이 3명이라 행동·특징 비교는 생략 (5명 미만, 표본 부족)")
                .doesNotContain("어떤 사람인가");
    }
}
```

`src/test/java/project/study/metrics/report/render/RosterRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;

class RosterRendererTest {

    @Test
    void 카드는_누구_공부_한_일_습관_네_줄이다() {
        assertThat(RosterRenderer.card(ReportSamples.bearCard(), ReportSamples.A))
                .containsExactly(
                        "🆕 공부하는곰(#418) · 9/29(화) 22시 가입 · 공무원(7급 합격) · iOS · 유입: 광고(instagram)",
                        "   공부: 첫 주 5일·11시간 20분(최장 연속 4일) → 둘째 주 4일·8시간 5분 → 마지막 공부 어제 · 지금 연속 3일",
                        "   첫 주에 한 일: 소셜 방 7회 · 과목 4개 · 할 일 완료 12개 · D-day · 간단 모드 · 가로 화면 · 백색소음 · 랭킹 · 앱 실행 6일",
                        "   습관: 세션 평균 48분 · 주로 21~24시 · 소셜 순공 62% · 일시정지 세션당 0.5회 · 딴짓 1시간당 1.2회");
    }

    @Test
    void 떠난_사람은_둘째_주_0일과_첫_주_마지막_공부를_쓴다() {
        assertThat(RosterRenderer.card(ReportSamples.leftCard(), ReportSamples.A).get(1))
                .isEqualTo("   공부: 첫 주 3일·1시간 50분(최장 연속 3일) → 둘째 주 0일 → 마지막 공부 8일 전(첫 주)");
    }

    @Test
    void 명단은_두_묶음과_빠진_사람_수를_쓴다() {
        RosterSection base = ReportSamples.roster();
        RosterSection section = new RosterSection(
                base.anchor(),
                base.cohortFrom(),
                base.cohortTo(),
                new RosterGroup(2, List.of(ReportSamples.bearCard()), LocalDate.of(2026, 9, 2)),
                base.dropped());

        assertThat(RosterRenderer.render(section).lines())
                .contains(
                        "🙋 첫 주에 3일 이상 공부한 3명 (가입 9/2~9/29)",
                        "🆕 = 어제로 둘째 주가 끝나 새로 들어온 사람(9/29 가입) · \"한 일\"은 첫 주 기준",
                        "✅ 둘째 주에도 공부한 2명",
                        "⋯ 외 1명 (가입 9/2 이전)",
                        "❌ 둘째 주에 안 온 1명");
    }

    @Test
    void 명단이_비면_한_줄로_쓴다() {
        RosterSection empty = new RosterSection(
                ReportSamples.A,
                ReportSamples.COHORT_FROM,
                ReportSamples.COHORT_TO,
                new RosterGroup(0, List.of(), null),
                new RosterGroup(0, List.of(), null));

        assertThat(RosterRenderer.render(empty)).isEqualTo("🙋 첫 주에 3일 이상 공부한 사람 없음 (가입 9/2~9/29)");
    }
}
```

`src/test/java/project/study/metrics/report/render/ExplorerRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.rule.Exposure;

class ExplorerRendererTest {

    @Test
    void 규칙마다_강도_연속_인원_비교를_쓰고_제외_이유를_센다() {
        assertThat(ExplorerRenderer.render(ReportSamples.explorer()).lines())
                .containsSubsequence(
                        "🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3",
                        "가입 9/2~9/29 252명 · 남은 사람 = 둘째 주에도 공부한 16명",
                        "연관 = 0.4 이상 강함 / 0.3~0.4 보통 / 0.2~0.3 약함",
                        "1. 첫 주에 소셜 방 3번 이상 참여 — 연관 강함 (0.53) · 3일째 1위",
                        "   10명 (4%) · 둘째 주에도 공부 70% (7/10) vs 안 한 사람 4% (9/242) · 남은 16명 중 7명이 했음",
                        "2. 첫 주에 공부한 날 3일 이상 (고정 세그먼트) — 연관 강함 (0.51) · 3일째 상위권",
                        "규칙 40개 중 통과 6개 · 제외: 사람 부족 14 · 너무 흔함 2 · 상관 약함 15 · Amplitude 미수집 3",
                        "⚠️ 상관관계이지 인과는 아님. 실험으로 확인할 후보");
    }

    @Test
    void 통과가_없고_고정_규칙이_빠지면_이유를_쓴다() {
        ExplorerSection section = new ExplorerSection(
                ReportSamples.COHORT_FROM,
                ReportSamples.COHORT_TO,
                12,
                1,
                List.of(),
                Map.of(Exposure.TOO_FEW, 40),
                Exposure.TOO_FEW);

        assertThat(ExplorerRenderer.render(section).lines())
                .contains(
                        "오늘은 조건을 통과한 규칙 없음(표본 부족)",
                        "규칙 40개 중 통과 0개 · 제외: 사람 부족 40 · 너무 흔함 0 · 상관 약함 0 · Amplitude 미수집 0",
                        "첫 주 3일 이상(고정 세그먼트)은 사람 부족으로 빠짐");
    }
}
```

`src/test/java/project/study/metrics/report/render/ActiveUsersRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;

class ActiveUsersRendererTest {

    @Test
    void 단계_기준과_단계별_한_줄_프로필을_쓴다() {
        assertThat(ActiveUsersRenderer.render(ReportSamples.active()).lines())
                .containsSubsequence(
                        "🧑‍🎓 어제 공부한 15명",
                        "단계 기준 (새로 온 사람이 먼저)",
                        "• 새로 온 사람: 어제가 가입 첫 주(가입일 포함 7일) 안",
                        "• 꾸준한 사람: 최근 7일(어제 포함) 중 3일 이상 공부",
                        "• 돌아온 사람: 첫 주가 지났고, 어제 전 14일 동안 공부 안 하다가 어제 공부 (가입 뒤 처음 공부한 사람 포함)",
                        "• 가끔 오는 사람: 나머지 — 최근 7일 중 1~2일 공부",
                        "🌱 새로 온 사람 6",
                        "• 아침형인간(#531) · 어제 가입 · 연속 1일 · 어제 순공 2시간 40분 · 싱글 위주 · 과목 2개 · 수능 · 최근 7일 앱 실행 1일",
                        "🔥 꾸준한 사람 5",
                        "• 공부하는곰(#418) · 가입 13일 뒤 · 연속 3일 · 어제 순공 3시간 30분 · 소셜 위주 · 과목 4개 · 공무원(7급 합격) · 최근 7일 앱 실행 6일",
                        "🔄 돌아온 사람 2",
                        "🌙 가끔 오는 사람 2",
                        "앱만 열고 공부 안 함: 9명");
    }

    @Test
    void 백_줄을_넘으면_나머지는_외_N명으로_줄이고_Amplitude가_없으면_그렇게_쓴다() {
        List<ActiveUserRow> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(new ActiveUserRow(i, "유저" + i, 1, 1, 600, false, 0, "분야 미입력", null));
        }
        String text = ActiveUsersRenderer.render(new ActiveUsersSection(101, Map.of(Stage.NEW, rows), null));

        assertThat(text.lines().filter(line -> line.startsWith("• 유저"))).hasSize(100);
        assertThat(text.lines()).contains("⋯ 외 1명", "앱만 열고 공부 안 함: Amplitude 미수집");
    }

    @Test
    void 어제_공부한_사람이_없으면_한_줄로_쓴다() {
        assertThat(ActiveUsersRenderer.render(new ActiveUsersSection(0, Map.of(), 3)))
                .isEqualTo("🧑‍🎓 어제 공부한 사람 없음\n\n앱만 열고 공부 안 함: 3명");
    }
}
```

`src/test/java/project/study/metrics/report/render/ReportRendererTest.java`:
```java
package project.study.metrics.report.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

class ReportRendererTest {

    @Test
    void 요약_하나와_스레드_여섯_개를_정해진_순서로_만든다() {
        RenderedReport rendered = ReportRenderer.render(ReportSamples.report());

        assertThat(rendered.summary()).startsWith("📊 10/12(월) 일일 지표");
        assertThat(rendered.threads())
                .hasSize(6)
                .satisfiesExactly(
                        text -> assertThat(text).startsWith("🕐 가입"),
                        text -> assertThat(text).startsWith("📈 다시 와서 공부한 비율"),
                        text -> assertThat(text).startsWith("👥 첫 주에 며칠 공부했나"),
                        text -> assertThat(text).startsWith("🙋 첫 주에 3일 이상 공부한"),
                        text -> assertThat(text).startsWith("🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3"),
                        text -> assertThat(text).startsWith("🧑‍🎓 어제 공부한"));
    }

    @Test
    void 계산에_실패한_섹션의_스레드는_계산_실패로_쓴다() {
        DailyReport base = ReportSamples.report();
        DailyReport report = new DailyReport(
                base.anchor(), SectionResult.failed(), base.retention(), base.segment(), base.roster(), base.explorer(),
                base.active());

        assertThat(ReportRenderer.render(report).threads().getFirst()).isEqualTo("🕐 가입\n계산 실패 — Sentry 확인");
    }

    @Test
    void 사만_자를_넘는_메시지는_잘라_알린다() {
        String capped = ReportRenderer.cap("가".repeat(50_000));

        assertThat(capped).hasSize(ReportRenderer.MAX_CHARS).endsWith("…(길이 상한 40,000자로 잘림)");
        assertThat(ReportRenderer.cap("짧은 글")).isEqualTo("짧은 글");
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.render.*"`
Expected: FAIL — `cannot find symbol: class SegmentRenderer`

- [ ] **Step 3: 렌더러를 만든다**

`src/main/java/project/study/metrics/report/render/SegmentRenderer.java`:
```java
package project.study.metrics.report.render;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.SegmentSection;
import project.study.metrics.report.analyzer.SegmentSection.BehaviorRow;
import project.study.metrics.report.analyzer.SegmentSection.Comparison;
import project.study.metrics.report.analyzer.SegmentSection.GroupRow;
import project.study.metrics.report.analyzer.SegmentSection.ShareRow;
import project.study.metrics.report.rule.Strength;
import project.study.metrics.report.rule.TwoByTwo;

/** 👥 첫 주 공부 일수별 비교 스레드 (부록 A 스레드 3). */
public final class SegmentRenderer {

    private static final int GROUP_LABEL_WIDTH = 16;
    private static final int PEOPLE_HEADER_WIDTH = 14;
    private static final int SIZE_WIDTH = 5;
    private static final int SHARE_WIDTH = 8;
    private static final int BEHAVIOR_WIDTH = 26;

    private SegmentRenderer() {}

    public static String render(SegmentSection section) {
        List<String> lines = new ArrayList<>();
        lines.add("👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나");
        lines.add("가입 %s %d명 · 첫 주 = 가입일 포함 7일 · 둘째 주 = 가입 7~13일 뒤"
                .formatted(Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize()));
        lines.add(Fmt.code(groupTable(section)));
        lines.add(comparison(section.table()));
        lines.add("");
        if (!section.enoughForBehaviors()) {
            lines.add("3일 이상이 %d명이라 행동·특징 비교는 생략 (5명 미만, 표본 부족)".formatted(section.table().doers()));
            return String.join("\n", lines);
        }
        lines.add("3일 이상인 사람이 첫 주에 주로 한 행동 (3일 이상 %d명 / 나머지 %d명)%s"
                .formatted(
                        section.table().doers(),
                        section.table().nonDoers(),
                        section.amplitudeCollected() ? "" : " · Amplitude 미수집 기간이라 앱 행동은 뺌"));
        lines.add(Fmt.code(behaviors(section)));
        lines.add("");
        lines.add("어떤 사람인가 (3일 이상 / 나머지)");
        section.who().forEach(comparison -> lines.add("• " + comparisonLine(comparison)));
        return String.join("\n", lines);
    }

    private static List<String> groupTable(SegmentSection section) {
        List<String> rows = new ArrayList<>();
        rows.add(Fmt.padRight("첫 주 공부한 날", GROUP_LABEL_WIDTH) + Fmt.padRight("사람", PEOPLE_HEADER_WIDTH) + "둘째 주에도 공부");
        for (GroupRow group : section.groups()) {
            String share = "(" + Fmt.percent(Rate.of(group.size(), section.cohortSize())) + ")";
            rows.add(Fmt.padRight(group.label(), GROUP_LABEL_WIDTH)
                    + Fmt.padLeft(group.size() + "명", SIZE_WIDTH)
                    + " "
                    + Fmt.padRight(share, SHARE_WIDTH)
                    + " "
                    + Fmt.rate(group.retained()));
        }
        return rows;
    }

    private static String comparison(TwoByTwo table) {
        Rate segment = table.doerRetention();
        Rate rest = table.nonDoerRetention();
        String gap = segment.defined() && rest.defined()
                ? " (%+d%%p)".formatted(segment.percent() - rest.percent())
                : "";
        double phi = table.phi();
        String link = Double.isNaN(phi) ? "연관 —" : "연관 %s (%s)".formatted(Strength.of(phi).label(), Fmt.phi(phi));
        return "→ 3일 이상 vs 나머지: %s vs %s%s · %s".formatted(Fmt.percent(segment), Fmt.percent(rest), gap, link);
    }

    private static List<String> behaviors(SegmentSection section) {
        List<String> lines = new ArrayList<>();
        if (section.behaviors().isEmpty()) {
            lines.add("(3일 이상의 30% 이상이 한 행동 없음)");
        }
        int number = 1;
        for (BehaviorRow row : section.behaviors()) {
            lines.add(behaviorLine(number++, row));
        }
        lines.add(behaviorLine(number, section.profile()) + "   ※ 지금 기준 — 입력 시점은 기록이 없음");
        return lines;
    }

    // 이름이 길어도 퍼센트와 붙지 않게 공백을 하나는 둔다
    private static String behaviorLine(int number, BehaviorRow row) {
        return Fmt.padRight(number + ". " + row.label() + " ", BEHAVIOR_WIDTH)
                + Fmt.percent(row.segment())
                + " / "
                + Fmt.percent(row.rest());
    }

    private static String comparisonLine(Comparison comparison) {
        List<ShareRow> rows = comparison.rows();
        if (rows.size() == 1 && rows.getFirst().label().isEmpty()) {
            return comparison.title() + ": " + pair(rows.getFirst());
        }
        return comparison.title() + ": "
                + rows.stream().map(row -> row.label() + " " + pair(row)).collect(joining(" · "));
    }

    private static String pair(ShareRow row) {
        return Fmt.percent(row.segment()) + " / " + Fmt.percent(row.rest());
    }
}
```

`src/main/java/project/study/metrics/report/render/RosterRenderer.java`:
```java
package project.study.metrics.report.render;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Kst;
import project.study.metrics.report.analyzer.RosterCard;
import project.study.metrics.report.analyzer.RosterCard.Habits;
import project.study.metrics.report.analyzer.RosterCard.StudyFlow;
import project.study.metrics.report.analyzer.RosterSection;
import project.study.metrics.report.analyzer.RosterSection.RosterGroup;

/** 🙋 명단 스레드 (부록 A 스레드 4). */
public final class RosterRenderer {

    private RosterRenderer() {}

    public static String render(RosterSection section) {
        String range = Fmt.range(section.cohortFrom(), section.cohortTo());
        if (section.total() == 0) {
            return "🙋 첫 주에 3일 이상 공부한 사람 없음 (가입 %s)".formatted(range);
        }
        List<String> lines = new ArrayList<>();
        lines.add("🙋 첫 주에 3일 이상 공부한 %d명 (가입 %s)".formatted(section.total(), range));
        lines.add("🆕 = 어제로 둘째 주가 끝나 새로 들어온 사람(%s 가입) · \"한 일\"은 첫 주 기준"
                .formatted(Fmt.monthDay(section.cohortTo())));
        lines.add("");
        group(lines, "✅ 둘째 주에도 공부한 %d명", section.retained(), section.anchor());
        lines.add("");
        group(lines, "❌ 둘째 주에 안 온 %d명", section.dropped(), section.anchor());
        return String.join("\n", lines);
    }

    static List<String> card(RosterCard card, LocalDate anchor) {
        return List.of(
                who(card),
                "   공부: " + flow(card.flow(), anchor),
                "   첫 주에 한 일: " + actions(card),
                "   습관: " + habits(card.habits()));
    }

    private static void group(List<String> lines, String title, RosterGroup group, LocalDate anchor) {
        lines.add(title.formatted(group.total()));
        group.cards().forEach(card -> lines.addAll(card(card, anchor)));
        if (group.omitted() > 0) {
            lines.add("⋯ 외 %d명 (가입 %s 이전)"
                    .formatted(group.omitted(), Fmt.monthDay(group.omittedSignupOnOrBefore())));
        }
    }

    private static String who(RosterCard card) {
        List<String> parts = new ArrayList<>();
        parts.add((card.fresh() ? "🆕 " : "") + card.nickname() + "(#" + card.userId() + ")");
        parts.add(Fmt.monthDayWeekday(Kst.date(card.signupAt())) + " " + Kst.hour(card.signupAt()) + "시 가입");
        parts.add(card.profile());
        if (card.os() != null) {
            parts.add(card.os());
        }
        parts.add(card.attribution());
        return String.join(" · ", parts);
    }

    private static String flow(StudyFlow flow, LocalDate anchor) {
        String first = "첫 주 %d일·%s(최장 연속 %d일)"
                .formatted(flow.firstWeekDays(), Fmt.duration(flow.firstWeekFocusSec()), flow.firstWeekLongestRun());
        String second = flow.secondWeekDays() == 0
                ? "둘째 주 0일"
                : "둘째 주 %d일·%s".formatted(flow.secondWeekDays(), Fmt.duration(flow.secondWeekFocusSec()));
        String streak = flow.currentStreak() > 0 ? " · 지금 연속 " + Fmt.streak(flow.currentStreak()) : "";
        return first + " → " + second + " → 마지막 공부 " + last(flow, anchor) + streak;
    }

    // 리포트는 A 다음 날 아침에 읽으니 A가 "어제"이고 그 전은 A+1 기준 며칠 전이다
    private static String last(StudyFlow flow, LocalDate anchor) {
        if (flow.lastStudyDay() == null) {
            return "없음";
        }
        if (flow.lastStudyDay().equals(anchor)) {
            return "어제";
        }
        long daysAgo = ChronoUnit.DAYS.between(flow.lastStudyDay(), anchor.plusDays(1));
        return daysAgo + "일 전" + (flow.lastInFirstWeek() ? "(첫 주)" : "");
    }

    private static String actions(RosterCard card) {
        String text = card.actions().isEmpty() ? "기록된 행동 없음" : String.join(" · ", card.actions());
        return card.amplitudePartial() ? text + " (Amplitude 일부 미수집)" : text;
    }

    private static String habits(Habits habits) {
        List<String> parts = new ArrayList<>();
        habits.averageSessionFocusSec()
                .ifPresent(value -> parts.add("세션 평균 " + Fmt.duration((int) Math.round(value))));
        habits.topBandStartHour().ifPresent(hour -> parts.add("주로 " + Kst.bandLabel(hour)));
        habits.socialPercent().ifPresent(percent -> parts.add("소셜 순공 " + percent + "%"));
        habits.pausesPerSession().ifPresent(value -> parts.add("일시정지 세션당 " + Fmt.decimal(value) + "회"));
        habits.distractionsPerHour().ifPresent(value -> parts.add("딴짓 1시간당 " + Fmt.decimal(value) + "회"));
        return parts.isEmpty() ? "기록 없음" : String.join(" · ", parts);
    }
}
```

`src/main/java/project/study/metrics/report/render/ExplorerRenderer.java`:
```java
package project.study.metrics.report.render;

import java.util.ArrayList;
import java.util.List;
import project.study.metrics.report.Rate;
import project.study.metrics.report.analyzer.ExplorerSection;
import project.study.metrics.report.analyzer.ExplorerSection.RankedRule;
import project.study.metrics.report.rule.Exposure;
import project.study.metrics.report.rule.TwoByTwo;

/** 🔍 자동 탐색 스레드 (부록 A 스레드 5). */
public final class ExplorerRenderer {

    private ExplorerRenderer() {}

    public static String render(ExplorerSection section) {
        List<String> lines = new ArrayList<>();
        lines.add("🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3");
        lines.add("가입 %s %d명 · 남은 사람 = 둘째 주에도 공부한 %d명"
                .formatted(Fmt.range(section.cohortFrom(), section.cohortTo()), section.cohortSize(), section.retained()));
        lines.add("연관 = 0.4 이상 강함 / 0.3~0.4 보통 / 0.2~0.3 약함");
        lines.add("");
        if (section.top().isEmpty()) {
            lines.add("오늘은 조건을 통과한 규칙 없음(표본 부족)");
        }
        for (RankedRule rule : section.top()) {
            lines.add(head(rule));
            lines.add(detail(rule, section));
        }
        lines.add("");
        lines.add(tally(section));
        if (section.fixedRuleExposure() != Exposure.PASS) {
            lines.add("첫 주 3일 이상(고정 세그먼트)은 %s으로 빠짐".formatted(section.fixedRuleExposure().label()));
        }
        lines.add("⚠️ 상관관계이지 인과는 아님. 실험으로 확인할 후보");
        return String.join("\n", lines);
    }

    private static String head(RankedRule rule) {
        return "%d. %s%s — 연관 %s (%s) · %s"
                .formatted(
                        rule.rank(),
                        rule.rule().name(),
                        rule.fixed() ? " (고정 세그먼트)" : "",
                        rule.strength().label(),
                        Fmt.phi(rule.table().phi()),
                        Fmt.trend(rule));
    }

    private static String detail(RankedRule rule, ExplorerSection section) {
        TwoByTwo table = rule.table();
        return "   %d명 (%s) · 둘째 주에도 공부 %s vs 안 한 사람 %s · 남은 %d명 중 %d명이 했음"
                .formatted(
                        table.doers(),
                        Fmt.percent(Rate.of(table.doers(), section.cohortSize())),
                        Fmt.rate(table.doerRetention()),
                        Fmt.rate(table.nonDoerRetention()),
                        section.retained(),
                        table.a());
    }

    private static String tally(ExplorerSection section) {
        return "규칙 %d개 중 통과 %d개 · 제외: 사람 부족 %d · 너무 흔함 %d · 상관 약함 %d · Amplitude 미수집 %d"
                .formatted(
                        section.ruleCount(),
                        section.passed(),
                        count(section, Exposure.TOO_FEW),
                        count(section, Exposure.TOO_COMMON),
                        count(section, Exposure.WEAK),
                        count(section, Exposure.NOT_COLLECTED));
    }

    private static int count(ExplorerSection section, Exposure exposure) {
        return section.tally().getOrDefault(exposure, 0);
    }
}
```

`src/main/java/project/study/metrics/report/render/ActiveUsersRenderer.java`:
```java
package project.study.metrics.report.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import project.study.metrics.report.analyzer.ActiveUsersSection;
import project.study.metrics.report.analyzer.ActiveUsersSection.ActiveUserRow;
import project.study.metrics.report.analyzer.ActiveUsersSection.Stage;

/** 🧑‍🎓 어제 공부한 유저 스레드 (부록 A 스레드 6). 단계 기준 문장을 스레드 첫머리에 그대로 쓴다. */
public final class ActiveUsersRenderer {

    static final int ROW_LIMIT = 100;

    private static final Map<Stage, String> ICONS =
            Map.of(Stage.NEW, "🌱", Stage.STEADY, "🔥", Stage.RETURNING, "🔄", Stage.OCCASIONAL, "🌙");
    private static final List<String> CRITERIA = List.of(
            "단계 기준 (새로 온 사람이 먼저)",
            "• 새로 온 사람: 어제가 가입 첫 주(가입일 포함 7일) 안",
            "• 꾸준한 사람: 최근 7일(어제 포함) 중 3일 이상 공부",
            "• 돌아온 사람: 첫 주가 지났고, 어제 전 14일 동안 공부 안 하다가 어제 공부 (가입 뒤 처음 공부한 사람 포함)",
            "• 가끔 오는 사람: 나머지 — 최근 7일 중 1~2일 공부");

    private ActiveUsersRenderer() {}

    public static String render(ActiveUsersSection section) {
        List<String> lines = new ArrayList<>();
        if (section.total() == 0) {
            lines.add("🧑‍🎓 어제 공부한 사람 없음");
        } else {
            lines.add("🧑‍🎓 어제 공부한 %d명".formatted(section.total()));
            lines.addAll(CRITERIA);
            lines.add("");
            lines.addAll(stages(section));
        }
        lines.add("");
        lines.add("앱만 열고 공부 안 함: " + (section.appOnly() == null ? "Amplitude 미수집" : section.appOnly() + "명"));
        return String.join("\n", lines);
    }

    // 메시지 전체 100줄 상한 — 넘는 사람은 "외 N명"으로 (스펙 §4.6)
    private static List<String> stages(ActiveUsersSection section) {
        List<String> lines = new ArrayList<>();
        int printed = 0;
        for (Stage stage : Stage.values()) {
            List<ActiveUserRow> rows = section.byStage().getOrDefault(stage, List.of());
            if (rows.isEmpty()) {
                continue;
            }
            lines.add(ICONS.get(stage) + " " + stage.label() + " " + rows.size());
            for (ActiveUserRow row : rows.subList(0, Math.min(rows.size(), ROW_LIMIT - printed))) {
                lines.add("• " + row(row));
                printed++;
            }
        }
        if (printed < section.total()) {
            lines.add("⋯ 외 %d명".formatted(section.total() - printed));
        }
        return lines;
    }

    private static String row(ActiveUserRow row) {
        String joined = row.daysSinceSignup() == 0 ? "어제 가입" : "가입 " + row.daysSinceSignup() + "일 뒤";
        String app = row.appDays7() == null ? "" : " · 최근 7일 앱 실행 " + row.appDays7() + "일";
        return "%s(#%d) · %s · 연속 %s · 어제 순공 %s · %s 위주 · 과목 %d개 · %s%s"
                .formatted(
                        row.nickname(),
                        row.userId(),
                        joined,
                        Fmt.streak(row.streak()),
                        Fmt.duration(row.focusSec()),
                        row.socialMostly() ? "소셜" : "싱글",
                        row.liveSubjects(),
                        row.profile(),
                        app);
    }
}
```

`src/main/java/project/study/metrics/report/render/RenderedReport.java`:
```java
package project.study.metrics.report.render;

import java.util.List;

/** Slack에 보낼 본 메시지 하나와 스레드 댓글들. */
public record RenderedReport(String summary, List<String> threads) {}
```

`src/main/java/project/study/metrics/report/render/ReportRenderer.java`:
```java
package project.study.metrics.report.render;

import io.sentry.Sentry;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

/** 섹션 결과 → 본 메시지 + 스레드 6개. 렌더링 버그도 그 스레드만 "계산 실패"로 막는다 (스펙 §10). */
@Slf4j
public final class ReportRenderer {

    /** Slack 메시지 하나의 failsafe 상한 (기존 리포트와 같은 값). */
    public static final int MAX_CHARS = 40_000;

    private static final String FAILED = "계산 실패 — Sentry 확인";
    private static final String CAPPED = "\n…(길이 상한 40,000자로 잘림)";

    private ReportRenderer() {}

    public static RenderedReport render(DailyReport report) {
        List<String> threads = List.of(
                thread(report.signup(), "🕐 가입", SignupRenderer::render),
                thread(report.retention(), "📈 다시 와서 공부한 비율", RetentionRenderer::render),
                thread(report.segment(), "👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나", SegmentRenderer::render),
                thread(report.roster(), "🙋 첫 주에 3일 이상 공부한 사람", RosterRenderer::render),
                thread(report.explorer(), "🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3", ExplorerRenderer::render),
                thread(report.active(), "🧑‍🎓 어제 공부한 사람", ActiveUsersRenderer::render));
        return new RenderedReport(
                cap(SummaryRenderer.render(report)),
                threads.stream().map(ReportRenderer::cap).toList());
    }

    public static String cap(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CHARS - CAPPED.length()) + CAPPED;
    }

    private static <T> String thread(SectionResult<T> result, String header, Function<T, String> renderer) {
        String failed = header + "\n" + FAILED;
        try {
            return result.value().map(renderer).orElse(failed);
        } catch (RuntimeException e) {
            log.error("일일 리포트 스레드 렌더링 실패 (스레드={})", header, e);
            Sentry.captureException(e);
            return failed;
        }
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.report.render.*"`
Expected: PASS (26 tests — Task 8의 렌더링 테스트 12개 포함)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/metrics/report/render src/test/java/project/study/metrics/report/render
git commit -m "feat: 첫 주 비교·명단·탐색·어제 공부 스레드를 렌더링한다 (BY-904)"
```

---
### Task 10: Slack 봇 발송과 10시 리포트 잡

**Files:**
- Modify: `src/main/java/project/study/metrics/slack/SlackNotifier.java` (전체 교체)
- Create: `src/main/java/project/study/metrics/slack/SlackBotNotifier.java`
- Create: `src/main/java/project/study/metrics/report/DailyReportService.java`, `DailyReportJob.java`
- Modify: `src/main/java/project/study/metrics/job/MetricsRunOnce.java`
- Modify: `src/main/resources/application-prod.yaml`, `src/main/resources/application-local.yaml.example`
- Modify: `src/test/java/project/study/config/FeatureSwitchTest.java`
- Delete: `src/main/java/project/study/metrics/slack/SlackWebhookNotifier.java`, `src/main/java/project/study/metrics/service/DailyReportService.java`,
  `src/main/java/project/study/metrics/scheduler/DailyReportScheduler.java`,
  `src/test/java/project/study/metrics/slack/SlackWebhookNotifierTest.java`, `src/test/java/project/study/metrics/service/DailyReportServiceTest.java`,
  `src/test/java/project/study/metrics/scheduler/DailyReportSchedulerTest.java`
- Test: `src/test/java/project/study/metrics/slack/SlackBotNotifierTest.java`, `src/test/java/project/study/metrics/report/DailyReportServiceTest.java`,
  `src/test/java/project/study/metrics/report/DailyReportJobTest.java`

**Interfaces:**
- Consumes: Task 3 `ReportDataLoader.load`, Task 8 `DailyReport.analyze`, Task 9 `ReportRenderer.render`, PR ① `MetricsJobRunner.submit(String, Runnable)`
- Produces:
  - `SlackNotifier` — `isEnabled()`, `@Retryable String post(String text)`(본 메시지 ts), `@Retryable void reply(String threadTs, String text)`
  - `SlackBotNotifier(RestClient.Builder, @Value("${metrics.slack.base-url:https://slack.com}") String, @Value("${metrics.slack.bot-token:}") String,
    @Value("${metrics.slack.channel-id:}") String)`
  - `DailyReportService.send()`, 생성자 `(ReportDataLoader, SlackNotifier, Clock, @Value("${metrics.slack.thread-delay-ms:1000}") long)`
  - `DailyReportJob.sendDailyReport()` — `@Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")`, `app.report.enabled=true`일 때만

- [ ] **Step 1: 발송기 테스트를 쓴다**

`src/test/java/project/study/metrics/slack/SlackBotNotifierTest.java`:
```java
package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.web.client.RestClient;

class SlackBotNotifierTest {

    // 자격증명 — 실패 메시지·원인 어디에도 나타나면 안 된다
    private static final String TOKEN = "xoxb-0000-verySecretBotToken";
    private static final String CHANNEL = "C0123456";

    private record Reply(int status, String retryAfter, String body) {}

    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat.postMessage", exchange -> {
            try (exchange) {
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Reply reply = replies.isEmpty() ? new Reply(500, null, "") : replies.poll();
                if (reply.retryAfter() != null) {
                    exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
                }
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SlackBotNotifier notifier() {
        return new SlackBotNotifier(RestClient.builder(), baseUrl(), TOKEN, CHANNEL);
    }

    private void reply(int status, String body) {
        replies.add(new Reply(status, null, body));
    }

    @Test
    void 토큰과_채널이_모두_있어야_켜진다() {
        assertThat(notifier().isEnabled()).isTrue();
        assertThat(new SlackBotNotifier(RestClient.builder(), baseUrl(), " ", CHANNEL).isEnabled()).isFalse();
        assertThat(new SlackBotNotifier(RestClient.builder(), baseUrl(), TOKEN, "").isEnabled()).isFalse();
    }

    @Test
    void 본_메시지를_봇_토큰으로_보내고_ts를_돌려준다() {
        reply(200, "{\"ok\":true,\"channel\":\"C0123456\",\"ts\":\"1700000000.000100\"}");

        assertThat(notifier().post("📊 요약")).isEqualTo("1700000000.000100");
        assertThat(authorizations).containsExactly("Bearer " + TOKEN);
        assertThat(bodies.getFirst())
                .contains("\"channel\":\"C0123456\"", "\"text\":\"📊 요약\"", "\"unfurl_links\":false", "\"unfurl_media\":false")
                .doesNotContain("thread_ts");
    }

    @Test
    void 스레드_댓글은_본_메시지_ts에_단다() {
        reply(200, "{\"ok\":true,\"ts\":\"1700000000.000200\"}");

        notifier().reply("1700000000.000100", "🕐 가입");

        assertThat(bodies.getFirst()).contains("\"thread_ts\":\"1700000000.000100\"");
    }

    @Test
    void ok가_false면_Slack_오류_코드로_실패한다() {
        reply(200, "{\"ok\":false,\"error\":\"not_in_channel\"}");

        assertThatThrownBy(() -> notifier().post("x"))
                .isInstanceOf(SlackNotificationException.class)
                .hasMessageContaining("not_in_channel")
                .hasNoCause();
    }

    @Test
    void ts가_없는_성공_응답은_실패로_본다() {
        reply(200, "{\"ok\":true}");

        // ts 없이 스레드를 달면 댓글이 채널 본문에 흩어진다
        assertThatThrownBy(() -> notifier().post("x")).isInstanceOf(SlackNotificationException.class);
    }

    @Test
    void 실패_메시지에_토큰을_남기지_않는다() {
        reply(500, "");

        Throwable serverError = catchThrowable(() -> notifier().post("x"));
        Throwable refused = catchThrowable(
                () -> new SlackBotNotifier(RestClient.builder(), "http://127.0.0.1:1", TOKEN, CHANNEL).post("x"));

        assertThat(serverError).isInstanceOf(SlackNotificationException.class).hasMessageContaining("HTTP 500").hasNoCause();
        assertThat(serverError.getMessage()).doesNotContain(TOKEN);
        assertThat(refused).isInstanceOf(SlackNotificationException.class).hasNoCause();
        assertThat(refused.getMessage()).doesNotContain(TOKEN);
    }

    @Test
    void 요청_한도에_걸리면_Retry_After만큼_기다렸다_한_번_더_보낸다() {
        replies.add(new Reply(429, "0", ""));
        reply(200, "{\"ok\":true,\"ts\":\"1.2\"}");

        assertThat(notifier().post("x")).isEqualTo("1.2");
        assertThat(bodies).hasSize(2);
    }

    @Test
    void 요청_한도가_이어지면_실패한다() {
        replies.add(new Reply(429, "0", ""));
        replies.add(new Reply(429, "0", ""));

        assertThatThrownBy(() -> notifier().post("x"))
                .isInstanceOf(SlackNotificationException.class)
                .hasMessageContaining("429");
    }

    @Test
    void 재시도_프록시를_거치면_세_번까지_보낸다() {
        reply(500, "");
        reply(500, "");
        reply(200, "{\"ok\":true,\"ts\":\"9.9\"}");

        try (AnnotationConfigApplicationContext context = retryingContext()) {
            assertThat(context.getBean(SlackNotifier.class).post("x")).isEqualTo("9.9");
        }
        assertThat(bodies).hasSize(3);
    }

    // @Retryable은 컨테이너가 만든 프록시를 거쳐야 동작한다
    private AnnotationConfigApplicationContext retryingContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RetryEnabledConfig.class);
        context.registerBean(SlackBotNotifier.class, this::notifier);
        context.refresh();
        return context;
    }

    @EnableResilientMethods
    @Configuration(proxyBeanMethods = false)
    private static class RetryEnabledConfig {}
}
```

- [ ] **Step 2: 서비스·잡 테스트를 쓴다**

`src/test/java/project/study/metrics/report/DailyReportServiceTest.java`:
```java
package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.slack.SlackNotificationException;
import project.study.metrics.slack.SlackNotifier;

@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    // 2026-10-13T01:00Z = KST 10:00 → 기준일(어제) 10/12
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    @Mock
    private ReportDataLoader loader;

    @Mock
    private SlackNotifier slackNotifier;

    private DailyReportService service() {
        return new DailyReportService(loader, slackNotifier, CLOCK, 0);
    }

    @Test
    void 발송기가_꺼져_있으면_계산도_하지_않는다() {
        when(slackNotifier.isEnabled()).thenReturn(false);

        service().send();

        verifyNoInteractions(loader);
        verify(slackNotifier, never()).post(anyString());
    }

    @Test
    void 어제_기준으로_요약을_보내고_그_ts에_스레드_여섯_개를_단다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(loader.load(A)).thenReturn(ReportDataFixture.data(A, true));
        when(slackNotifier.post(startsWith("📊 10/12(월) 일일 지표"))).thenReturn("ts-1");

        service().send();

        verify(slackNotifier, times(6)).reply(eq("ts-1"), anyString());
        verify(slackNotifier).reply(eq("ts-1"), startsWith("🕐 가입"));
        verify(slackNotifier).reply(eq("ts-1"), startsWith("🧑‍🎓 어제 공부한"));
    }

    @Test
    void 스레드_하나가_실패해도_나머지를_보낸다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(loader.load(A)).thenReturn(ReportDataFixture.data(A, true));
        when(slackNotifier.post(anyString())).thenReturn("ts-1");
        doThrow(new SlackNotificationException("Slack API 응답 실패 (HTTP 500)"))
                .when(slackNotifier)
                .reply(eq("ts-1"), startsWith("🕐 가입"));
        doNothing().when(slackNotifier).reply(eq("ts-1"), org.mockito.ArgumentMatchers.argThat(text -> !text.startsWith("🕐 가입")));

        service().send();

        verify(slackNotifier, times(6)).reply(eq("ts-1"), anyString());
    }

    @Test
    void 본_메시지가_실패하면_스레드를_달지_않는다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(loader.load(A)).thenReturn(ReportDataFixture.data(A, true));
        when(slackNotifier.post(anyString())).thenThrow(new SlackNotificationException("Slack API 호출 실패"));

        assertThatThrownBy(() -> service().send()).isInstanceOf(SlackNotificationException.class);
        verify(slackNotifier, never()).reply(anyString(), anyString());
    }
}
```

`src/test/java/project/study/metrics/report/DailyReportJobTest.java`:
```java
package project.study.metrics.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.job.MetricsJobRunner;

@ExtendWith(MockitoExtension.class)
class DailyReportJobTest {

    @Mock
    private MetricsJobRunner runner;

    @Mock
    private DailyReportService reportService;

    @Test
    void 스케줄은_지표_전용_스레드에_제출만_한다() {
        new DailyReportJob(runner, reportService).sendDailyReport();

        verify(runner).submit(eq("daily-report"), any(Runnable.class));
    }
}
```

`FeatureSwitchTest`의 리포트 부분을 바꾼다:
- import `project.study.metrics.scheduler.DailyReportScheduler`, `project.study.metrics.service.DailyReportService`를
  `project.study.metrics.report.DailyReportJob`, `project.study.metrics.report.DailyReportService`로 바꾼다.
- `report` 러너:
```java
    private final ApplicationContextRunner report = new ApplicationContextRunner()
            .withBean(MetricsJobRunner.class, () -> mock(MetricsJobRunner.class))
            .withBean(DailyReportService.class, () -> mock(DailyReportService.class))
            .withUserConfiguration(DailyReportJob.class);
```
- `amplitudeIngest` 러너에 `.withBean(DailyReportService.class, () -> mock(DailyReportService.class))`를 더한다(MetricsRunOnce가 리포트도 돌린다).
- 테스트 두 개의 이름과 대상:
```java
    @Test
    void 일일_리포트_잡은_스위치가_없으면_뜨지_않는다() {
        report.run(context -> assertThat(context).doesNotHaveBean(DailyReportJob.class));
    }

    @Test
    void 일일_리포트_잡은_스위치를_켜면_뜬다() {
        report.withPropertyValues("app.report.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DailyReportJob.class));
    }
```

- [ ] **Step 3: 테스트가 실패하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.slack.SlackBotNotifierTest" --tests "project.study.metrics.report.DailyReportServiceTest" --tests "project.study.metrics.report.DailyReportJobTest"`
Expected: FAIL — `cannot find symbol: class SlackBotNotifier` (옛 테스트·옛 클래스는 다음 단계에서 지운다)

- [ ] **Step 4: 옛 리포트 경로를 지우고 발송기 인터페이스를 바꾼다**

```bash
git rm src/main/java/project/study/metrics/slack/SlackWebhookNotifier.java \
  src/main/java/project/study/metrics/service/DailyReportService.java \
  src/main/java/project/study/metrics/scheduler/DailyReportScheduler.java \
  src/test/java/project/study/metrics/slack/SlackWebhookNotifierTest.java \
  src/test/java/project/study/metrics/service/DailyReportServiceTest.java \
  src/test/java/project/study/metrics/scheduler/DailyReportSchedulerTest.java
```

`src/main/java/project/study/metrics/slack/SlackNotifier.java`(전체 교체):
```java
package project.study.metrics.slack;

import org.springframework.resilience.annotation.Retryable;

/**
 * Slack 발송 — 본 메시지를 올리고 그 ts에 스레드 댓글을 단다(스펙 §9). 인터페이스로 두어 단위 테스트에서 실제 HTTP 없이 대체한다.
 *
 * <p>@Retryable은 컨테이너가 만든 프록시를 거칠 때만 동작하고, 구현체는 인터페이스 기반 JDK 동적 프록시로 감싸지므로 이 인터페이스
 * 메서드에 붙인다. 최초 포함 3회, 하루 한 번 도는 작업이라 짧은 고정 간격이다.
 */
public interface SlackNotifier {

    /** 봇 토큰·채널이 설정돼 있는지. 꺼져 있으면 호출자는 리포트 계산부터 건너뛴다. */
    boolean isEnabled();

    /** 본 메시지를 올리고 스레드를 달 ts를 돌려준다. */
    @Retryable(includes = SlackNotificationException.class, maxRetries = 2, delay = 200)
    String post(String text);

    @Retryable(includes = SlackNotificationException.class, maxRetries = 2, delay = 200)
    void reply(String threadTs, String text);
}
```

`src/main/java/project/study/metrics/slack/SlackBotNotifier.java`:
```java
package project.study.metrics.slack;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Slack 봇(`chat.postMessage`) 발송기 — Incoming Webhook은 스레드를 못 달아 바꿨다(ADR-0030 결정 8). 봇 권한은 `chat:write`, 채널 초대가 필요하다.
 *
 * <p>봇 토큰은 자격증명이라 예외 메시지·로그 어디에도 남기지 않고 원인 예외도 붙이지 않는다(SlackNotificationException 참고).
 */
@Slf4j
@Component
public class SlackBotNotifier implements SlackNotifier {

    // 응답이 안 오는 연결에 지표 스레드가 묶이지 않게 (옛 웹훅 발송기와 같은 값)
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_RETRY_AFTER_SEC = 30;
    private static final int TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;
    private final String token;
    private final String channel;

    private record SlackReply(boolean ok, String ts, String error, long retryAfterSec) {
        static SlackReply rateLimited(long retryAfterSec) {
            return new SlackReply(false, null, null, retryAfterSec);
        }

        boolean limited() {
            return retryAfterSec >= 0;
        }
    }

    public SlackBotNotifier(
            RestClient.Builder restClientBuilder,
            @Value("${metrics.slack.base-url:https://slack.com}") String baseUrl,
            @Value("${metrics.slack.bot-token:}") String token,
            @Value("${metrics.slack.channel-id:}") String channel) {
        // 공유 싱글턴 빌더를 clone()해 이 발송기 전용 타임아웃만 얹는다 — 원본을 건드리면 전역 동작이 바뀐다
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.token = token == null ? "" : token.strip();
        this.channel = channel == null ? "" : channel.strip();
        if (!isEnabled()) {
            log.warn("metrics.slack.bot-token·channel-id 중 빈 값이 있어 일일 지표 리포트를 보내지 않는다");
        }
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(token) && StringUtils.hasText(channel);
    }

    @Override
    public String post(String text) {
        String ts = call(body(text, null));
        if (!StringUtils.hasText(ts)) {
            throw new SlackNotificationException("Slack API 응답에 ts가 없다");
        }
        return ts;
    }

    @Override
    public void reply(String threadTs, String text) {
        call(body(text, threadTs));
    }

    private Map<String, Object> body(String text, String threadTs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("text", text);
        if (threadTs != null) {
            body.put("thread_ts", threadTs);
        }
        body.put("unfurl_links", false);
        body.put("unfurl_media", false);
        return body;
    }

    // 429는 Retry-After초 뒤 한 번 더 — 그래도 막히면 @Retryable이 짧은 간격으로 두 번 더 시도한다
    private String call(Map<String, Object> body) {
        if (!isEnabled()) {
            throw new SlackNotificationException("Slack 봇 설정이 없다");
        }
        SlackReply reply = exchange(body);
        if (reply.limited()) {
            sleep(reply.retryAfterSec());
            reply = exchange(body);
        }
        if (reply.limited()) {
            throw new SlackNotificationException("Slack 요청 한도 초과 (HTTP 429)");
        }
        if (!reply.ok()) {
            throw new SlackNotificationException("Slack API 오류 (%s)".formatted(reply.error()));
        }
        return reply.ts();
    }

    private SlackReply exchange(Map<String, Object> body) {
        try {
            return restClient
                    .post()
                    .uri("/api/chat.postMessage")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> read(response));
        } catch (RestClientException e) {
            throw new SlackNotificationException(
                    "Slack API 호출 실패 (%s)".formatted(e.getClass().getSimpleName()));
        }
    }

    private static SlackReply read(ConvertibleClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        if (status == TOO_MANY_REQUESTS) {
            return SlackReply.rateLimited(retryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (status < 200 || status >= 300) {
            throw new SlackNotificationException("Slack API 응답 실패 (HTTP %d)".formatted(status));
        }
        JsonNode json = response.bodyTo(JsonNode.class);
        if (json == null) {
            throw new SlackNotificationException("Slack API 응답 본문이 비었다");
        }
        return new SlackReply(json.path("ok").asBoolean(false), text(json, "ts"), text(json, "error"), -1);
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static long retryAfter(String header) {
        try {
            return Math.clamp(Long.parseLong(header == null ? "1" : header.strip()), 0, MAX_RETRY_AFTER_SEC);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlackNotificationException("Slack 재시도 대기 중 중단됐다");
        }
    }
}
```

- [ ] **Step 5: 리포트 서비스와 잡을 만든다**

`src/main/java/project/study/metrics/report/DailyReportService.java`:
```java
package project.study.metrics.report;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.study.metrics.report.render.RenderedReport;
import project.study.metrics.report.render.ReportRenderer;
import project.study.metrics.slack.SlackNotificationException;
import project.study.metrics.slack.SlackNotifier;

/**
 * 일일 지표 리포트 v2를 계산해 Slack 본 메시지 + 스레드 6개로 보낸다 (BY-904, 스펙 §9·§10).
 *
 * <p>하루 1회 발송을 DB로 보장하지 않는다 — 날짜를 선점한 뒤 발송이 실패하면 그날 리포트가 영구히 유실되고 이력이 "보냈다"고 거짓
 * 상태를 남긴다. 중복 발송(태스크 2개)은 시끄럽지만 무해하고, 원본 데이터가 남아 리포트는 언제든 다시 계산할 수 있다(BY-461 결정 유지).
 */
@Slf4j
@Service
public class DailyReportService {

    private final ReportDataLoader loader;
    private final SlackNotifier slackNotifier;
    private final Clock clock;
    private final long threadDelayMs;

    public DailyReportService(
            ReportDataLoader loader,
            SlackNotifier slackNotifier,
            Clock clock,
            @Value("${metrics.slack.thread-delay-ms:1000}") long threadDelayMs) {
        this.loader = loader;
        this.slackNotifier = slackNotifier;
        this.clock = clock;
        this.threadDelayMs = threadDelayMs;
    }

    public void send() {
        if (!slackNotifier.isEnabled()) {
            log.warn("Slack 봇 설정이 없어 일일 리포트를 건너뛴다");
            return;
        }
        // 발송 시각이 10시라 오늘은 부분 집계다. 완결된 하루인 어제를 기준일로 쓴다
        LocalDate anchor = LocalDate.ofInstant(clock.instant(), Kst.ZONE).minusDays(1);
        RenderedReport rendered = ReportRenderer.render(DailyReport.analyze(loader.load(anchor)));
        String threadTs = slackNotifier.post(rendered.summary());
        int sent = 0;
        for (String thread : rendered.threads()) {
            pause();
            sent += reply(threadTs, thread) ? 1 : 0;
        }
        log.info("일일 지표 리포트를 발송했다 (기준일={}, 스레드 {}/{})", anchor, sent, rendered.threads().size());
    }

    // 스레드 하나가 실패해도 나머지는 보낸다 — 재시도(@Retryable)까지 실패한 댓글만 건너뛴다
    private boolean reply(String threadTs, String text) {
        try {
            slackNotifier.reply(threadTs, text);
            return true;
        } catch (SlackNotificationException e) {
            log.error("일일 리포트 스레드 댓글 발송 실패 — 건너뛴다", e);
            Sentry.captureException(e);
            return false;
        }
    }

    // Slack 채널당 초당 1건 권장 한도에 맞춘 간격 (테스트는 0)
    private void pause() {
        if (threadDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(threadDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("일일 리포트 발송 중 중단됐다", e);
        }
    }
}
```

`src/main/java/project/study/metrics/report/DailyReportJob.java`:
```java
package project.study.metrics.report;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.metrics.job.MetricsJobRunner;

/**
 * 매일 10:00(KST) 일일 리포트 — {@code app.report.enabled=true}(prod yaml)일 때만 뜬다(BY-640).
 *
 * <p>지표 전용 단일 스레드에 제출만 한다. 09:00 Amplitude 적재가 같은 스레드에 먼저 들어가 있으면 적재가 끝난 뒤 돈다.
 * zone을 명시하는 이유: ECS 태스크는 UTC로 뜬다. ECS 태스크가 2개 이상이면 태스크마다 발송한다(중복은 무해, 스펙 §10).
 */
@Component
@ConditionalOnProperty(name = "app.report.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DailyReportJob {

    private final MetricsJobRunner runner;
    private final DailyReportService reportService;

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendDailyReport() {
        runner.submit("daily-report", reportService::send);
    }
}
```

`MetricsRunOnce`를 바꾼다(필드 `private final DailyReportService reportService;` 추가, import `project.study.metrics.report.DailyReportService`):
```java
    @Override
    public void run(ApplicationArguments args) {
        // 단일 스레드라 적재가 끝난 뒤 리포트가 돈다
        runner.submit("amplitude-ingest(run-once)", ingestService::ingestPending);
        runner.submit("daily-report(run-once)", reportService::send);
    }
```
클래스 Javadoc의 "지표 작업을 한 번 돌린다"는 그대로 맞다.

- [ ] **Step 6: 설정을 바꾼다**

`application-prod.yaml`의 `app.report` 주석 두 줄을 다음으로 바꾼다:
```yaml
  # 일일 지표 스케줄(09:00 Amplitude 적재, 10:00 리포트) — 운영에서만 켠다. 값이 없으면 꺼짐이 기본이라 prod yaml이 명시적으로 켠다 (BY-640).
  # 발송은 metrics.slack.bot-token·channel-id가 있어야 한다 (없으면 리포트 계산부터 건너뜀)
```

`application-prod.yaml`의 웹훅 블록(`# 일일 지표 리포트를 보낼 Slack Incoming Webhook.`부터 `webhook-url: ${SLACK_WEBHOOK_URL:}`까지)을 다음으로 바꾼다:
```yaml
# 일일 지표 리포트를 보낼 Slack 봇(chat.postMessage, 권한 chat:write — 채널에 초대해야 한다)과 채널 ID (BY-904).
# 값이 없으면 발송기가 비활성으로 동작한다 — 모니터링 설정 하나가 없다고 API 전체가 기동 실패하는 쪽이 더 나쁘다.
# ECS 태스크 정의가 SSM Parameter Store 값을 SLACK_BOT_TOKEN·SLACK_CHANNEL_ID로 주입한다.
metrics:
  slack:
    bot-token: ${SLACK_BOT_TOKEN:}
    channel-id: ${SLACK_CHANNEL_ID:}
  report:
    # 리포트에서 뺄 회원번호(팀 내부 계정 등), 쉼표로 구분
    excluded-user-ids: ${REPORT_EXCLUDED_USER_IDS:}
```
(그 아래 `amplitude:` 블록은 그대로.)

`application-local.yaml.example`의 BY-904 블록을 다음으로 바꾼다:
```yaml
# 일일 리포트·Amplitude 적재를 로컬에서 한 번 돌려 보려면 키를 넣고 run-once를 켠다 (BY-904).
# 키·토큰은 이 파일(gitignore)에만 둔다. 리포트는 테스트 채널로 보낸다
# metrics:
#   amplitude:
#     api-key:
#     secret-key:
#     backfill-days: 3
#   slack:
#     bot-token:
#     channel-id:
#   report:
#     excluded-user-ids:
# app:
#   report:
#     run-once: true
```

- [ ] **Step 7: 테스트가 통과하는지 확인한다**

Run: `./gradlew test --tests "project.study.metrics.*" --tests "project.study.config.FeatureSwitchTest"`
Expected: PASS (옛 리포트 테스트는 지워졌다)

- [ ] **Step 8: 커밋**

```bash
git add -A src/main/java/project/study/metrics src/test/java/project/study/metrics src/test/java/project/study/config/FeatureSwitchTest.java \
  src/main/resources/application-prod.yaml src/main/resources/application-local.yaml.example
git commit -m "feat: 일일 리포트 v2를 Slack 봇 스레드로 보낸다 (BY-904)"
```

---

### Task 11: 옛 지표 조회 정리

**Files:**
- Delete: `src/main/java/project/study/metrics/dto/` 전체(`CohortFirstWeek`, `DailyReport`, `FocusStat`, `HeavyUser`, `NewUser`, `QualifyingSession`,
  `RoomVsSingleFocus`, `UserSessionDetail`), `src/main/java/project/study/studysession/repository/StudySessionMetricsRepository.java`,
  `QualifyingSessionRow.java`
- Delete: `src/test/java/project/study/metrics/dto/` 전체, `src/test/java/project/study/metrics/MetricsQueryIntegrationTest.java`,
  `src/test/java/project/study/studysession/HeavyUserQueryIntegrationTest.java`, `CohortFirstWeekQueryIntegrationTest.java`,
  `QualifyingSessionQueryIntegrationTest.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionMetricsService.java` (전체 교체),
  `src/main/java/project/study/user/service/UserService.java`, `src/main/java/project/study/user/repository/UserRepository.java`,
  `src/test/java/project/study/user/service/UserServiceTest.java`, `docs/adr/0012-*.md`

**Interfaces:**
- Consumes: Task 1 `SessionFactQueries`, Task 2 `UserTimelineTest.자정_분할로_조각난_세션은_각_조각이_10분_미만이면_공부한_날이_아니다`
- Produces: 없음 (지우기만 한다)

- [ ] **Step 1: 옛 코드와 테스트를 지운다**

```bash
git rm -r src/main/java/project/study/metrics/dto src/test/java/project/study/metrics/dto
git rm src/main/java/project/study/studysession/repository/StudySessionMetricsRepository.java \
  src/main/java/project/study/studysession/repository/QualifyingSessionRow.java \
  src/test/java/project/study/metrics/MetricsQueryIntegrationTest.java \
  src/test/java/project/study/studysession/HeavyUserQueryIntegrationTest.java \
  src/test/java/project/study/studysession/CohortFirstWeekQueryIntegrationTest.java \
  src/test/java/project/study/studysession/QualifyingSessionQueryIntegrationTest.java
```

- [ ] **Step 2: 남은 참조를 정리한다**

`StudySessionMetricsService`(전체 교체):
```java
package project.study.studysession.service;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.report.fact.SessionFact;
import project.study.studysession.repository.SessionFactQueries;

/**
 * 지표 집계 전용 서비스(매일 10시 Slack 리포트) — 앱 화면용 StudySessionService와 나눠, 지표가 늘어도 화면 서비스 파일이 커지지
 * 않게 한다. 공부한 날 판정은 리포트가 세션 단위로 한다(ADR-0012 결정 3, UserTimeline).
 */
@Service
@RequiredArgsConstructor
public class StudySessionMetricsService {

    private final SessionFactQueries sessionFactQueries;

    /** 일일 리포트 v2 — 기간(양끝 포함) 안 모든 세션을 일시정지 수·소셜 여부와 함께 (BY-904). */
    @Transactional(readOnly = true)
    public List<SessionFact> findSessionFacts(LocalDate from, LocalDate to) {
        return sessionFactQueries.findBetween(from, to);
    }
}
```

`UserService`에서 `countTotal()`, `countRegisteredOn(LocalDate)`, `findNewUsersOn(LocalDate)`과 import `project.study.metrics.dto.NewUser`를 지운다.
그 뒤 안 쓰이게 된 `KST`·`HOUR_MINUTE` 상수와 import(`ZoneId`, `DateTimeFormatter`, `LocalDate` 등)를 `./gradlew compileJava`·Checkstyle
`UnusedImports`가 가리키는 대로 지운다.

`UserRepository`에서 `countByCreatedAtGreaterThanEqualAndCreatedAtLessThan`과
`findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAt`을 지운다.

`UserServiceTest`에서 `신규가입_목록은_유저ID와_KST_가입시각을_담는다` 테스트와 import `project.study.metrics.dto.NewUser`를 지운다
(안 쓰이게 된 import도 함께).

`docs/adr/0012-*.md` "결과" 절 끝에 한 줄을 더한다:
```markdown
- **개정(2026-10-13, BY-904):** 헤비유저·코호트 첫주 조회는 일일 리포트 v2(ADR-0030)로 대체돼 지웠다. 자정 분할 조각을 병합하지
  않는 규칙(결정 3)은 `UserTimelineTest.자정_분할로_조각난_세션은_각_조각이_10분_미만이면_공부한_날이_아니다`가 이어받는다 —
  이 테스트를 고칠 일이 생기면 먼저 이 ADR을 개정한다.
```

- [ ] **Step 3: 컴파일·테스트를 확인한다**

Run: `./gradlew compileJava compileTestJava && ./gradlew test --tests "project.study.user.*" --tests "project.study.studysession.*" --tests "project.study.metrics.*"`
Expected: BUILD SUCCESSFUL, 지운 클래스를 참조하는 곳 없음

- [ ] **Step 4: 커밋**

```bash
git add -A src/main/java src/test/java docs/adr
git commit -m "refactor: 옛 일일 리포트 지표 조회를 지운다 (BY-904)"
```

---

### Task 12: 문서 정리와 전체 검증

**Files:**
- Modify: `docs/superpowers/specs/2026-10-13-daily-report-v2-design.md` (부록 A 두 곳)

- [ ] **Step 1: 스펙을 구현과 맞춘다**

- 부록 A 본 메시지 4번째 줄: `첫 주에 소셜 방 3번 이상 (연관 강함, 3일째 1위)` → `첫 주에 소셜 방 3번 이상 참여 (연관 강함, 3일째 1위)`
  (§4.7 템플릿 블록도 같은 줄이 있으면 함께).
- 부록 A 스레드 5의 2·3위 상세 줄을 1위와 같은 긴 형식으로 바꾼다:
  `   14명 (6%) · 둘째 주에도 공부 57% (8/14) vs 안 한 사람 3% (8/238) · 남은 16명 중 8명이 했음`,
  `   34명 (13%) · 둘째 주에도 공부 35% (12/34) vs 안 한 사람 2% (4/218) · 남은 16명 중 12명이 했음`.

- [ ] **Step 2: 전체 검증**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (테스트·Spotless·Checkstyle·ArchUnit). Spotless 위반이면 `./gradlew spotlessApply` 후 다시 실행한다.

- [ ] **Step 3: Codex 2차 리뷰 (CLAUDE.md 크로스 코드체크)**

Run: `codex exec -s read-only "backend 리포의 현재 브랜치에서 origin/dev 대비 PR ② 변경(git diff 77801b5..HEAD -- src)을 리뷰해줘. 맥락: 일일 Slack 리포트 v2 — 도메인 사실 조회 → UserTimeline → 분석기 6개(가입·리텐션 구간·첫 주 3일+ 비교·명단·규칙 40개 phi 탐색·어제 공부 단계) → 렌더러 → Slack 봇 chat.postMessage 본 메시지+스레드 6개. 설계 docs/superpowers/specs/2026-10-13-daily-report-v2-design.md, 계획 docs/superpowers/plans/2026-10-13-daily-report-v2-pr2-report.md. 날짜 경계(KST·D0~D6·D7~D13·구간 리텐션), 0 나누기·NaN, Amplitude 미수집 처리, 제외 유저 누락, 토큰 유출, 429·재시도, 스레드 부분 실패, Checkstyle 한도를 P1/P2/P3로 지적해줘"`
Expected: P1 없음. P1·P2가 나오면 실패 테스트를 먼저 쓰고 고친 뒤 `./gradlew check`를 다시 통과시킨다.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/specs/2026-10-13-daily-report-v2-design.md
git commit -m "docs: 일일 리포트 v2 스펙을 구현과 맞춘다 (BY-904)"
```

---

## 배포 전 준비 (코드 밖, 사용자 확인 필요)

1. Slack 앱에 봇 토큰(`chat:write`)을 만들고 리포트 채널에 초대한다. 채널 ID를 확인한다.
2. infra: SSM `slack/bot-token`·`slack/channel-id` 파라미터와 ECS 컨테이너 시크릿 `SLACK_BOT_TOKEN`·`SLACK_CHANNEL_ID`를 더한다
   (Amplitude 키와 같은 방식, 값은 사용자가 직접 넣는다). 필요하면 `REPORT_EXCLUDED_USER_IDS`도.
3. PR ① 배포 → 백필·매일 적재 확인 → PR ② 배포. dev는 dev Amplitude 키 + 테스트 채널로 `run-once`를 먼저 돌려 본다.
4. PR ② 배포 확인 뒤 infra에서 `SLACK_WEBHOOK_URL`을 지운다(스펙 §13).
