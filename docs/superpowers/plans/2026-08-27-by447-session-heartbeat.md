# BY-447 세션 진행 스냅샷·자동 확정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공부 중 30초마다 진행 스냅샷을 받는 `PUT /api/study-sessions/active`와, 보고가 끊긴 세션을 자동 확정하는 스케줄러를 추가해 미제출 세션 유실을 막는다.

**Architecture:** 진행중 스냅샷은 별도 draft 테이블(`active_study_session`)에 세션당 1행 UPSERT — 확정 테이블 `study_session`의 "행 = 확정 세션" 불변식은 유지. 확정은 기존 `StudySessionService.create`를 재사용(검증·자정 분할·멱등)하고, 자동 확정본은 `auto_finalized` 플래그로 표시해 늦은 최종 제출·재확정이 무조건 대체한다.

**Tech Stack:** Spring Boot 4.1 / Java 25 / PostgreSQL(Flyway) / Testcontainers 2.0 / MockMvcTester / Jackson 3(`tools.jackson`)

**Spec:** `docs/superpowers/specs/2026-08-27-by447-session-heartbeat-design.md`

## Global Constraints

- 커밋 전 반드시 `./gradlew check` 통과 (테스트+Spotless+ArchUnit). 포맷 실패 시 `./gradlew spotlessApply` 후 재실행
- Spring Boot **4.x** — Jackson 3은 `tools.jackson` 패키지(`com.fasterxml` 아님), Testcontainers 2.0, MockMvc 대신 `MockMvcTester`
- DB 변경은 Flyway 마이그레이션으로만, `ddl-auto: validate` 고정. 다음 번호는 **V12**
- DTO는 record, 생성자 주입만(`@RequiredArgsConstructor`), 엔티티는 `@Getter` + protected 기본 생성자, `@Data` 금지
- 커밋 컨벤션 `<type>: <설명>`, 한 커밋 한 목적. 브랜치는 `feature/BY-447-세션-진행-스냅샷` (이미 생성됨)
- 테스트 파일 400줄 제한 (기존 관례 — 넘으면 파일 분리)
- 시간은 `java.time.Clock` 빈 주입으로 얻는다 (`Instant.now()` 직접 호출 금지 — 스케줄러 제외한 서비스 로직)

---

### Task 1: V12 마이그레이션 + ActiveStudySession 엔티티 + 리포지토리

`active_study_session` 테이블과 `study_session.auto_finalized` 컬럼을 만들고, 엔티티·리포지토리 저장/조회 라운드트립을 검증한다. `ddl-auto: validate`라서 스키마·엔티티 불일치는 컨텍스트 기동 실패로 즉시 드러난다.

**Files:**
- Create: `src/main/resources/db/migration/V12__active_study_session.sql`
- Create: `src/main/java/project/study/studysession/entity/ActiveStudySession.java`
- Create: `src/main/java/project/study/studysession/repository/ActiveStudySessionRepository.java`
- Modify: `src/main/java/project/study/studysession/entity/StudySession.java` (autoFinalized 필드)
- Test: `src/test/java/project/study/studysession/ActiveStudySessionRepositoryTest.java`

**Interfaces:**
- Produces: `ActiveStudySession` 읽기 전용 엔티티 — Lombok getter(`getId/getUserId/getStartedAt/getReportedAt/getLastSeenAt/getStudySec/getFocusSec/getEvents`). 쓰기는 전부 네이티브 UPSERT라 공개 생성자·수정 메서드가 없다
- Produces: `ActiveStudySessionRepository` — `int upsertSnapshot(Long userId, Instant startedAt, Instant reportedAt, Instant lastSeenAt, int studySec, int focusSec, String events)`(네이티브 ON CONFLICT UPSERT + 역순 가드), `Optional<ActiveStudySession> findByUserIdAndStartedAt(Long, Instant)`, `List<ActiveStudySession> findByLastSeenAtBefore(Instant)`, `void deleteByUserIdAndStartedAt(Long, Instant)`
- Produces: `StudySession.isAutoFinalized()`, `StudySession.markAutoFinalized()`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/ActiveStudySessionRepositoryTest.java`:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.entity.ActiveStudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;

/** BY-447 draft 테이블 라운드트립 — jsonb 매핑과 파생 쿼리가 실제 PostgreSQL에서 동작하는지 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveStudySessionRepositoryTest {

    @Autowired
    private ActiveStudySessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    private final Instant startedAt = Instant.parse("2026-08-27T01:00:00Z");

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private int upsert(Instant reported, int studySec, int focusSec, String events) {
        return repository.upsertSnapshot(
                userId, startedAt, reported, Instant.parse("2026-08-27T01:10:05Z"), studySec, focusSec, events);
    }

    @Test
    void 없으면_INSERT되고_유저와_시작시각으로_다시_찾는다() {
        String events = "[{\"status\":\"PHONE\",\"startedAt\":\"2026-08-27T01:05:00Z\",\"endedAt\":\"2026-08-27T01:07:00Z\"}]";
        int affected = upsert(startedAt.plusSeconds(600), 600, 480, events);

        assertThat(affected).isEqualTo(1);
        ActiveStudySession found =
                repository.findByUserIdAndStartedAt(userId, startedAt).orElseThrow();
        assertThat(found.getReportedAt()).isEqualTo(startedAt.plusSeconds(600));
        assertThat(found.getStudySec()).isEqualTo(600);
        assertThat(found.getFocusSec()).isEqualTo(480);
        assertThat(found.getEvents()).contains("PHONE");
    }

    @Test
    void 있으면_행을_늘리지_않고_통째로_갱신된다() {
        upsert(startedAt.plusSeconds(30), 30, 30, "[]");
        int affected = upsert(startedAt.plusSeconds(60), 60, 55, "[]");

        assertThat(affected).isEqualTo(1);
        ActiveStudySession updated =
                repository.findByUserIdAndStartedAt(userId, startedAt).orElseThrow();
        assertThat(updated.getStudySec()).isEqualTo(60);
        assertThat(repository.findByLastSeenAtBefore(Instant.parse("2026-08-27T02:00:00Z")))
                .hasSize(1);
    }

    @Test
    void 저장된_것보다_과거_reportedAt은_0행_갱신으로_무시된다() {
        upsert(startedAt.plusSeconds(60), 60, 55, "[]");
        int affected = upsert(startedAt.plusSeconds(30), 30, 30, "[]");

        assertThat(affected).isEqualTo(0);
        ActiveStudySession kept =
                repository.findByUserIdAndStartedAt(userId, startedAt).orElseThrow();
        assertThat(kept.getStudySec()).isEqualTo(60);
    }

    @Test
    void lastSeenAt이_기준보다_오래된_draft만_조회된다() {
        upsert(startedAt.plusSeconds(30), 30, 30, "[]");

        assertThat(repository.findByLastSeenAtBefore(Instant.parse("2026-08-27T01:10:06Z")))
                .hasSize(1);
        assertThat(repository.findByLastSeenAtBefore(Instant.parse("2026-08-27T01:10:05Z")))
                .isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveStudySessionRepositoryTest"`
Expected: 컴파일 실패 (`ActiveStudySession` 없음)

- [ ] **Step 3: 마이그레이션 작성**

`src/main/resources/db/migration/V12__active_study_session.sql`:

```sql
-- BY-447: 진행중 세션 스냅샷(draft) 테이블 — 30초마다 UPSERT, 확정 시 삭제.
-- reported_at(클라 시계)은 확정 시 ended_at이 되고, last_seen_at(서버 시계)은 무응답 판정 기준이다.
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

-- 자동 확정본 표시 — true인 세션은 잠정 기록이라 늦은 최종 제출·재확정이 대체할 수 있다
ALTER TABLE study_session ADD COLUMN auto_finalized BOOLEAN NOT NULL DEFAULT false;
```

- [ ] **Step 4: 엔티티 작성**

`src/main/java/project/study/studysession/entity/ActiveStudySession.java`:

```java
package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 진행중 세션의 최신 스냅샷(draft) — 세션당 1행, 매 하트비트마다 통째로 덮어쓴다 (BY-447).
 * 확정 시 StudySessionService.create로 넘겨 study_session 행이 되고 이 행은 삭제된다.
 * reportedAt(클라 시계)은 확정 시 endedAt이 되고, lastSeenAt(서버 시계)은 무응답 판정에만 쓴다 —
 * 클라 시계가 느린 유저의 공부 중 세션이 무응답으로 오판되지 않게 두 시계의 역할을 분리한다.
 * 쓰기는 전부 리포지토리의 네이티브 UPSERT로만 한다 — 이 엔티티는 읽기 전용이라 공개 생성자·수정 메서드가 없다.
 */
@Entity
@Table(name = "active_study_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActiveStudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "study_sec", nullable = false)
    private Integer studySec;

    @Column(name = "focus_sec", nullable = false)
    private Integer focusSec;

    // 이벤트 전체 스냅샷(StatusEventRequest 배열의 JSON) — status_event 자식 행은 확정 시점에만 만든다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "events", nullable = false)
    private String events;
}
```

- [ ] **Step 5: 리포지토리 작성**

`src/main/java/project/study/studysession/repository/ActiveStudySessionRepository.java`:

```java
package project.study.studysession.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import project.study.studysession.entity.ActiveStudySession;

public interface ActiveStudySessionRepository extends JpaRepository<ActiveStudySession, Long> {

    /**
     * 스냅샷 UPSERT — 한 문장으로 동시 첫 스냅샷 레이스(ON CONFLICT)와 역순 도착 가드(WHERE)를
     * 원자적으로 해결한다. 레이스 패자·과거 reportedAt 스냅샷은 조용히 0행 갱신(반환 0)으로 끝난다.
     * 조회-후-갱신 방식은 유니크 충돌 flush 실패가 트랜잭션을 오염시켜(rollback-only) 못 쓴다.
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO active_study_session
                        (user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec, events)
                    VALUES (:userId, :startedAt, :reportedAt, :lastSeenAt, :studySec, :focusSec, cast(:events as jsonb))
                    ON CONFLICT (user_id, started_at) DO UPDATE
                    SET reported_at = excluded.reported_at,
                        last_seen_at = excluded.last_seen_at,
                        study_sec = excluded.study_sec,
                        focus_sec = excluded.focus_sec,
                        events = excluded.events
                    WHERE active_study_session.reported_at < excluded.reported_at""",
            nativeQuery = true)
    int upsertSnapshot(
            @Param("userId") Long userId,
            @Param("startedAt") Instant startedAt,
            @Param("reportedAt") Instant reportedAt,
            @Param("lastSeenAt") Instant lastSeenAt,
            @Param("studySec") int studySec,
            @Param("focusSec") int focusSec,
            @Param("events") String events);

    // 확정 대상 draft 조회 — (userId, startedAt)이 draft의 멱등 키다
    Optional<ActiveStudySession> findByUserIdAndStartedAt(Long userId, Instant startedAt);

    // 확정 스케줄러용 — 서버 시계 기준 무응답 draft. 테이블 크기가 동시 공부 세션 수라 인덱스 불필요
    List<ActiveStudySession> findByLastSeenAtBefore(Instant cutoff);

    // 최종 제출 성공 시 같은 트랜잭션에서 draft 정리 — 없으면 no-op
    void deleteByUserIdAndStartedAt(Long userId, Instant startedAt);
}
```

- [ ] **Step 6: StudySession에 autoFinalized 추가**

`src/main/java/project/study/studysession/entity/StudySession.java` — 필드와 메서드 추가:

```java
    // BY-447: 자동 확정본 표시 — true인 세션은 잠정 기록이라 늦은 최종 제출·재확정이 대체할 수 있다
    @Column(name = "auto_finalized", nullable = false)
    private boolean autoFinalized;
```

기존 생성자는 그대로 두고(기본값 false), `attachToSubmission` 아래에 추가:

```java
    /** 확정 스케줄러가 만든 세션임을 표시한다 — 저장 직전 서비스만 호출한다. */
    public void markAutoFinalized() {
        this.autoFinalized = true;
    }
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveStudySessionRepositoryTest"`
Expected: PASS (3개). `ddl-auto: validate`가 통과했다는 것 자체가 스키마·엔티티 일치의 증거다.

- [ ] **Step 8: 전체 검증 후 커밋**

```bash
./gradlew check
git add src/main/resources/db/migration/V12__active_study_session.sql \
    src/main/java/project/study/studysession/entity/ActiveStudySession.java \
    src/main/java/project/study/studysession/entity/StudySession.java \
    src/main/java/project/study/studysession/repository/ActiveStudySessionRepository.java \
    src/test/java/project/study/studysession/ActiveStudySessionRepositoryTest.java
git commit -m "feat: 진행중 세션 draft 테이블·엔티티 추가 (BY-447)"
```

---

### Task 2: 하트비트 API — DTO·서비스·컨트롤러

`PUT /api/study-sessions/active`. 검증은 `StudySessionService.createSessions`(package-private)를 호출해 결과를 버리는 방식으로 재사용한다 — 확정 시 실행될 코드와 같은 경로라, draft가 항상 확정 가능함이 구조적으로 보장된다.

**Files:**
- Create: `src/main/java/project/study/studysession/dto/ActiveSessionSnapshotRequest.java`
- Create: `src/main/java/project/study/studysession/service/ActiveStudySessionService.java`
- Create: `src/main/java/project/study/studysession/controller/ActiveStudySessionController.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java` (violatedConstraint를 package-private으로)
- Test: `src/test/java/project/study/studysession/ActiveSessionSnapshotApiTest.java`

**Interfaces:**
- Consumes: Task 1의 `ActiveStudySessionRepository.upsertSnapshot`
- Consumes: 기존 `StudySessionService.createSessions(Long, Instant, Instant, int, int, List<StatusEvent>)` (package-private, 같은 패키지), `StatusEventRequest.toEntity()`
- Produces: `ActiveStudySessionService.reportSnapshot(ActiveSessionSnapshotRequest)` — Task 4가 같은 서비스에 확정 메서드를 추가한다
- Produces: `StudySessionService.violatedConstraint(DataIntegrityViolationException)` package-private static

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/ActiveSessionSnapshotApiTest.java`:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** BY-447 진행 스냅샷 보고 API — 30초마다 오는 누적 스냅샷의 UPSERT·역순 무시·검증을 다룬다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ActiveSessionSnapshotApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    // 검증(미래 시각 금지)이 실제 시계를 쓰므로 항상 과거인 시각을 기준으로 잡는다
    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult report(Long uid, Instant started, Instant reported, int studySec, int focusSec) {
        return report(uid, started, reported, studySec, focusSec, "[]");
    }

    private MvcTestResult report(
            Long uid, Instant started, Instant reported, int studySec, int focusSec, String eventsJson) {
        String body =
                """
                {"userId": %d, "startedAt": "%s", "reportedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s}"""
                        .formatted(uid, started, reported, studySec, focusSec, eventsJson);
        return mvc.put()
                .uri("/api/study-sessions/active")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private Integer draftRows(Long uid) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, uid);
    }

    private Integer draftStudySec(Long uid) {
        return jdbcTemplate.queryForObject(
                "SELECT study_sec FROM active_study_session WHERE user_id = ?", Integer.class, uid);
    }

    @Test
    void 첫_스냅샷은_draft를_만든다() {
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 30, 30))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(draftRows(userId)).isEqualTo(1);
    }

    @Test
    void 두번째_스냅샷은_행을_늘리지_않고_덮어쓴다() {
        report(userId, startedAt, startedAt.plusSeconds(30), 30, 30);
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 60, 55))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(draftRows(userId)).isEqualTo(1);
        assertThat(draftStudySec(userId)).isEqualTo(60);
    }

    @Test
    void 저장된_것보다_과거_reportedAt은_조용히_무시된다() {
        report(userId, startedAt, startedAt.plusSeconds(60), 60, 55);
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 30, 30))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(draftStudySec(userId)).isEqualTo(60);
    }

    @Test
    void 이벤트_스냅샷이_jsonb로_저장된다() {
        String events =
                """
                [{"status":"PHONE","startedAt":"%s","endedAt":"%s"}]"""
                        .formatted(startedAt.plusSeconds(10), startedAt.plusSeconds(20));
        assertThat(report(userId, startedAt, startedAt.plusSeconds(30), 20, 10, events))
                .hasStatus(HttpStatus.NO_CONTENT);

        String stored = jdbcTemplate.queryForObject(
                "SELECT events::text FROM active_study_session WHERE user_id = ?", String.class, userId);
        assertThat(stored).contains("PHONE");
    }

    @Test
    void reportedAt이_startedAt_이전이면_400이다() {
        assertThat(report(userId, startedAt, startedAt, 0, 0)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(draftRows(userId)).isEqualTo(0);
    }

    @Test
    void focusSec이_studySec을_넘으면_400이다() {
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 30, 40))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 이벤트가_세션_구간_밖이면_400이다() {
        String events =
                """
                [{"status":"AWAY","startedAt":"%s","endedAt":"%s"}]"""
                        .formatted(startedAt.plusSeconds(50), startedAt.plusSeconds(90));
        assertThat(report(userId, startedAt, startedAt.plusSeconds(60), 30, 20, events))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 존재하지_않는_유저는_404다() {
        assertThat(report(999_999L, startedAt, startedAt.plusSeconds(30), 30, 30))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 필수값_누락은_400이다() {
        String body = """
                {"userId": %d, "startedAt": "%s"}""".formatted(userId, startedAt);
        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionSnapshotApiTest"`
Expected: 전부 FAIL (엔드포인트 없음 — MockMvcTester는 404 또는 405를 받는다)

- [ ] **Step 3: DTO 작성**

`src/main/java/project/study/studysession/dto/ActiveSessionSnapshotRequest.java`:

```java
package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** 진행중 세션의 누적 스냅샷 — 30초마다 통째로 보내 서버 draft를 덮어쓴다 (BY-447). */
public record ActiveSessionSnapshotRequest(
        @Schema(description = "세션 주인의 유저 ID", example = "1") @NotNull Long userId,

        @Schema(
                description = "세션 시작 시각 (UTC, ISO-8601) — 최종 제출의 startedAt과 같은 값이어야 한다. "
                        + "userId와 함께 draft의 멱등 키로 쓰인다",
                example = "2026-08-27T01:00:00Z")
        @NotNull
        Instant startedAt,

        @Schema(
                description = "이 스냅샷의 기준 시점(지금 시각, 클라이언트 시계). 세션이 자동 확정되면 이 값이 endedAt이 된다. "
                        + "startedAt 이후·미래 아님(5분 허용) 규칙은 최종 제출의 endedAt과 동일하다. "
                        + "저장된 스냅샷보다 과거면 조용히 무시된다(역순 도착)",
                example = "2026-08-27T01:10:30Z")
        @NotNull
        Instant reportedAt,

        @Schema(description = "지금까지의 누적 총 공부 시간(초) — 최종 제출 studySec과 같은 검증 규칙", example = "600")
        @NotNull
        Integer studySec,

        @Schema(description = "지금까지의 누적 순공 시간(초) — 0 이상 studySec 이하", example = "540") @NotNull
        Integer focusSec,

        @Schema(description = "지금까지의 비공부 이벤트 전체 — 진행 중인 이벤트는 reportedAt에서 닫아서 보낸다. 없으면 []")
        @NotNull
        @Valid
        List<StatusEventRequest> events) {}
```

- [ ] **Step 4: StudySessionService의 violatedConstraint를 package-private으로**

`StudySessionService.java`의 `private static String violatedConstraint(...)`를 `static String violatedConstraint(...)`로 변경 (같은 패키지의 `ActiveStudySessionService`가 재사용).

- [ ] **Step 5: 서비스 작성**

`src/main/java/project/study/studysession/service/ActiveStudySessionService.java`:

```java
package project.study.studysession.service;

import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import project.study.common.NotFoundException;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.repository.ActiveStudySessionRepository;
import tools.jackson.databind.ObjectMapper;

/** 진행중 세션 스냅샷(draft) 관리 — 하트비트 UPSERT (BY-447). 확정은 스케줄러 태스크에서 추가한다. */
@Service
@RequiredArgsConstructor
public class ActiveStudySessionService {

    // V12가 이름 없이 만든 FK의 PostgreSQL 자동 명명 규칙 이름
    private static final String USER_FK_CONSTRAINT = "active_study_session_user_id_fkey";

    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final StudySessionService studySessionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 누적 스냅샷을 draft에 UPSERT한다. 검증은 확정 시 실행될 createSessions를 그대로 호출하고
     * 결과를 버리는 방식으로 재사용한다 — draft가 항상 확정 가능한 상태임을 같은 코드 경로로 보장한다.
     * 동시 INSERT 레이스와 역순 도착은 네이티브 UPSERT가 원자적으로 걸러 조용히 무시된다(0행 갱신).
     */
    public void reportSnapshot(ActiveSessionSnapshotRequest request) {
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        studySessionService.createSessions(
                request.userId(),
                request.startedAt(),
                request.reportedAt(),
                request.studySec(),
                request.focusSec(),
                events);

        String eventsJson = objectMapper.writeValueAsString(request.events());
        try {
            activeStudySessionRepository.upsertSnapshot(
                    request.userId(),
                    request.startedAt(),
                    request.reportedAt(),
                    clock.instant(),
                    request.studySec(),
                    request.focusSec(),
                    eventsJson);
        } catch (DataIntegrityViolationException e) {
            String constraint = StudySessionService.violatedConstraint(e);
            if (USER_FK_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new NotFoundException("존재하지 않는 사용자입니다: " + request.userId());
            }
            throw e;
        }
    }
}
```

참고: `createSessions`는 package-private이지만 Spring CGLIB 프록시가 같은 패키지에 생성되므로 프록시 경유 호출이 정상 위임된다. 만약 실행 시 프록시 위임 문제(NPE)가 보이면 `createSessions`를 public으로 올려 해결한다 — 동작은 동일하다.

- [ ] **Step 6: 컨트롤러 작성**

`src/main/java/project/study/studysession/controller/ActiveStudySessionController.java`:

```java
package project.study.studysession.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.service.ActiveStudySessionService;

@Tag(
        name = "StudySession",
        description = "공부 세션 기록 API 모음 — 방 퇴장 시 세션 전체를 한 번에 제출받아 검증·계산·저장한다 (ADR-0003). 통계 조회는 StudySessionStats 참고")
@RestController
@RequestMapping("/api/study-sessions")
@RequiredArgsConstructor
public class ActiveStudySessionController {

    private final ActiveStudySessionService activeStudySessionService;

    @Operation(summary = "진행중 세션 스냅샷 보고", description = """
                    공부 중 30초마다 진행중 세션의 누적 스냅샷을 보고한다 (BY-447, ADR-0014). \
                    앱이 최종 제출 없이 죽어도 서버가 마지막 스냅샷 기준으로 세션을 자동 확정해 유실을 막는다 — \
                    최악 유실은 마지막 스냅샷 이후 30초.

                    **스냅샷은 누적값이다.** 매 보고가 "지금까지의 studySec/focusSec + 비공부 이벤트 전체"를 담고 \
                    서버 draft를 통째로 덮어쓴다(멱등 PUT). 진행 중인 이벤트는 reportedAt에서 닫아서 보낸다 — \
                    다음 스냅샷이 덮어쓰므로 자연히 갱신된다. 저장된 스냅샷보다 reportedAt이 과거인 보고는 조용히 무시된다.

                    **검증은 최종 제출과 같은 규칙이다** — endedAt 자리에 reportedAt을 두고 시각 순서·24시간 한도·\
                    미래 금지(5분 허용)·studySec/focusSec 범위·이벤트 겹침/구간 검증을 모두 적용한다.

                    **자동 확정** — 마지막 보고가 서버 기준 5분 넘게 끊기면 스케줄러가 reportedAt을 종료 시각으로 \
                    세션을 확정한다(자정 분할 포함, 길이 무관). 자동 확정본은 잠정 기록이라, 이후 같은 startedAt으로 \
                    최종 제출이 도착하면 그것으로 대체된다. 정상 최종 제출 시 draft는 함께 삭제되므로 \
                    앱은 확정 여부를 신경 쓸 필요 없이 늘 하던 대로 제출하면 된다.""")
    @ApiResponse(responseCode = "204", description = "스냅샷 반영 완료 (역순 도착으로 무시된 경우 포함)")
    @ApiResponse(
            responseCode = "400",
            description = "검증 실패 — 최종 제출과 같은 시간·범위·이벤트 규칙",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(
                                            name = "기준 시점이 시작보다 빠름",
                                            value = "{\"message\": \"세션 종료 시각은 시작 시각 이후여야 합니다\"}")))
    @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 userId",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples =
                                    @ExampleObject(name = "유저 없음", value = "{\"message\": \"존재하지 않는 사용자입니다: 999\"}")))
    @PutMapping("/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody ActiveSessionSnapshotRequest request) {
        activeStudySessionService.reportSnapshot(request);
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionSnapshotApiTest"`
Expected: PASS (9개)

- [ ] **Step 8: 전체 검증 후 커밋**

```bash
./gradlew check
git add src/main/java/project/study/studysession/dto/ActiveSessionSnapshotRequest.java \
    src/main/java/project/study/studysession/service/ActiveStudySessionService.java \
    src/main/java/project/study/studysession/service/StudySessionService.java \
    src/main/java/project/study/studysession/controller/ActiveStudySessionController.java \
    src/test/java/project/study/studysession/ActiveSessionSnapshotApiTest.java
git commit -m "feat: 진행중 세션 스냅샷 보고 API 추가 (BY-447)"
```

---

### Task 3: create 대체 정책 — auto_finalized 세션은 잠정 기록

`StudySessionService.create`에 (1) `autoFinalized` 마킹 오버로드, (2) 기존 조각이 전부 자동 확정본이면 삭제 후 대체하는 분기, (3) 저장 성공 시 draft 삭제를 추가한다. 대체 판단에 호출자 구분이 필요 없다는 점이 핵심 — "기존이 전부 auto면 대체, 하나라도 클라본이면 반환" 규칙 하나가 클라 재제출·자동 확정 후 최종 제출·재확정 세 경우를 모두 처리한다.

**Files:**
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java`
- Test: `src/test/java/project/study/studysession/AutoFinalizedReplaceApiTest.java`

**Interfaces:**
- Consumes: Task 1의 `StudySession.isAutoFinalized()/markAutoFinalized()`, `ActiveStudySessionRepository.deleteByUserIdAndStartedAt`
- Produces: `StudySessionService.create(Long userId, StudySessionCreateRequest request, boolean autoFinalized)` — Task 4의 확정 로직이 `autoFinalized=true`로 호출한다. 기존 `create(userId, request)` 시그니처·동작(클라 제출 경로)은 변경 없음

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/AutoFinalizedReplaceApiTest.java`:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/** BY-447 대체 정책 — auto_finalized 세션은 잠정 기록이라 늦은 최종 제출이 대체하고, 클라 제출본은 불가침이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AutoFinalizedReplaceApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    /** 자동 확정된 세션 흉내 — 스케줄러가 만든 것과 같은 형태의 행을 직접 넣는다. */
    private void insertAutoFinalizedSession(Instant started, Instant ended, int studySec, int focusSec) {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, true)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(started, KST)),
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(ended),
                studySec,
                focusSec);
    }

    private void insertDraft(Instant started, Instant reported, int studySec, int focusSec) {
        jdbcTemplate.update(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb)",
                userId,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(reported),
                java.sql.Timestamp.from(reported),
                studySec,
                focusSec);
    }

    private MvcTestResult submit(Instant started, Instant ended, int studySec, int focusSec) {
        String body =
                """
                {"userId": %d, "startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}"""
                        .formatted(userId, started, ended, studySec, focusSec);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private Integer sessionRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    private Integer draftRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 자동_확정본은_늦은_최종_제출로_대체된다() {
        insertAutoFinalizedSession(startedAt, startedAt.plusSeconds(1800), 1800, 1700);

        MvcTestResult result = submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400);
        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", sec -> assertThat(sec).isEqualTo(3600));

        assertThat(sessionRows()).isEqualTo(1);
        Boolean autoFinalized = jdbcTemplate.queryForObject(
                "SELECT auto_finalized FROM study_session WHERE user_id = ?", Boolean.class, userId);
        assertThat(autoFinalized).isFalse();
    }

    @Test
    void 클라_제출본은_재제출이_와도_대체되지_않는다() {
        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);

        MvcTestResult replay = submit(startedAt, startedAt.plusSeconds(7000), 7000, 6500);
        assertThat(replay)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", sec -> assertThat(sec).isEqualTo(3600));

        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    void 최종_제출이_성공하면_같은_세션의_draft가_삭제된다() {
        insertDraft(startedAt, startedAt.plusSeconds(1800), 1800, 1700);

        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);

        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 멱등_반환_경로에서는_draft를_지우지_않는다() {
        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);
        // 제출 후 뒤늦은 하트비트가 draft를 되살린 상황 — 재제출은 멱등 반환만 하고 draft는 스케줄러가 정리한다
        insertDraft(startedAt, startedAt.plusSeconds(3900), 3900, 3600);

        assertThat(submit(startedAt, startedAt.plusSeconds(3600), 3600, 3400)).hasStatus(HttpStatus.CREATED);

        assertThat(draftRows()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.AutoFinalizedReplaceApiTest"`
Expected: `자동_확정본은_늦은_최종_제출로_대체된다`(멱등 반환돼 studySec 1800), `최종_제출이_성공하면...`(draft 잔존) FAIL. 나머지 2개는 기존 동작이라 PASS여도 정상.

- [ ] **Step 3: create 수정**

`StudySessionService.java` — 필드에 `ActiveStudySessionRepository` 추가:

```java
    private final StudySessionRepository studySessionRepository;
    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final Clock clock;
```

기존 `create`를 오버로드로 위임하고 대체 분기·draft 정리를 추가:

```java
    @Transactional
    public List<StudySessionResponse> create(Long userId, StudySessionCreateRequest request) {
        return create(userId, request, false);
    }

    /** autoFinalized=true는 확정 스케줄러 전용 — 저장되는 세션에 자동 확정 표시를 남긴다. */
    @Transactional
    public List<StudySessionResponse> create(Long userId, StudySessionCreateRequest request, boolean autoFinalized) {
        List<StudySession> existing = studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(
                userId, request.startedAt());
        if (!existing.isEmpty()) {
            // 클라 제출본이 하나라도 있으면 불가침 — 기존 멱등 동작(저장된 결과 반환)
            if (!existing.stream().allMatch(StudySession::isAutoFinalized)) {
                return existing.stream().map(this::toResponse).toList();
            }
            // 전부 자동 확정본이면 잠정 기록 — 새 도착분(최종 제출·재확정)으로 대체한다.
            // 길이 비교는 하지 않는다: 스냅샷이 누적값이라 나중 도착분이 항상 상위집합이다 (ADR-0014)
            studySessionRepository.deleteAll(existing);
            studySessionRepository.flush();
        }
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        List<StudySession> sessions = createSessions(
                userId, request.startedAt(), request.endedAt(), request.studySec(), request.focusSec(), events);
        if (autoFinalized) {
            sessions.forEach(StudySession::markAutoFinalized);
        }
        try {
            List<StudySession> saved = studySessionRepository.saveAll(sessions);
            studySessionRepository.flush();
            activeStudySessionRepository.deleteByUserIdAndStartedAt(userId, request.startedAt());
            return saved.stream().map(this::toResponse).toList();
        } catch (DataIntegrityViolationException e) {
            String constraint = violatedConstraint(e);
            if (STARTED_AT_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new DuplicateSessionException("이미 같은 시각에 시작한 세션이 저장되어 있습니다");
            }
            if (USER_FK_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new NotFoundException("존재하지 않는 사용자입니다: " + userId);
            }
            throw e;
        }
    }
```

(import에 `project.study.studysession.repository.ActiveStudySessionRepository` 추가)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.AutoFinalizedReplaceApiTest"`
Expected: PASS (4개)

- [ ] **Step 5: 기존 멱등·세션 테스트 회귀 확인**

Run: `./gradlew test --tests "project.study.studysession.*"`
Expected: 전부 PASS — 클라 제출 경로의 기존 동작(멱등 반환·409·자정 분할)은 그대로여야 한다

- [ ] **Step 6: 전체 검증 후 커밋**

```bash
./gradlew check
git add src/main/java/project/study/studysession/service/StudySessionService.java \
    src/test/java/project/study/studysession/AutoFinalizedReplaceApiTest.java
git commit -m "feat: 자동 확정 세션 대체 정책 추가 (BY-447)"
```

---

### Task 4: 확정 스케줄러 — 무응답 draft 자동 확정

`ActiveStudySessionService`에 확정 메서드들을 추가하고, 1분 주기 스케줄러가 서버 시계 기준 5분 무응답 draft를 확정한다. draft별 독립 트랜잭션 — 검증 예외로 확정 트랜잭션이 롤백돼도 draft 폐기는 스케줄러가 별도 트랜잭션으로 수행한다.

**Files:**
- Modify: `src/main/java/project/study/studysession/service/ActiveStudySessionService.java`
- Create: `src/main/java/project/study/studysession/scheduler/ActiveSessionFinalizeScheduler.java`
- Modify: `src/test/resources/application.yaml` (테스트에서 스케줄러 비활성)
- Test: `src/test/java/project/study/studysession/ActiveSessionFinalizeTest.java`

**Interfaces:**
- Consumes: Task 3의 `StudySessionService.create(userId, request, true)`, Task 1의 `ActiveStudySessionRepository.findByLastSeenAtBefore`
- Produces: `ActiveStudySessionService.findStaleDraftIds()`, `finalizeDraft(Long draftId)`, `discardDraft(Long draftId)` — 스케줄러가 이 순서로 호출한다

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/ActiveSessionFinalizeTest.java`:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.service.ActiveStudySessionService;

/** BY-447 무응답 draft 자동 확정 — 스케줄러가 호출하는 서비스 메서드를 직접 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveSessionFinalizeTest {

    @Autowired
    private ActiveStudySessionService activeStudySessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Long userId;

    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Long insertDraft(Instant started, Instant reported, Instant lastSeen, int studySec, int focusSec, String events) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO active_study_session (user_id, started_at, reported_at, last_seen_at, study_sec,"
                        + " focus_sec, events) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
                Long.class,
                userId,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(reported),
                java.sql.Timestamp.from(lastSeen),
                studySec,
                focusSec,
                events);
    }

    private Instant staleLastSeen() {
        return Instant.now().minusSeconds(600); // 유예 5분보다 오래됨
    }

    private Integer sessionRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    private Integer draftRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 무응답_draft가_세션으로_확정되고_draft는_삭제된다() {
        String events =
                """
                [{"status":"PHONE","startedAt":"%s","endedAt":"%s"}]"""
                        .formatted(startedAt.plusSeconds(100), startedAt.plusSeconds(200));
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, events);

        assertThat(activeStudySessionService.findStaleDraftIds()).contains(draftId);
        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(1);
        assertThat(draftRows()).isEqualTo(0);
        Boolean autoFinalized = jdbcTemplate.queryForObject(
                "SELECT auto_finalized FROM study_session WHERE user_id = ?", Boolean.class, userId);
        assertThat(autoFinalized).isTrue();
        // jsonb 이벤트가 status_event 행으로 복원된다
        Integer eventRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM status_event e JOIN study_session s ON e.session_id = s.id"
                        + " WHERE s.user_id = ?",
                Integer.class,
                userId);
        assertThat(eventRows).isEqualTo(1);
    }

    @Test
    void 자정을_걸친_draft는_두_조각으로_확정된다() {
        Instant midnight = LocalDate.now(KST).atStartOfDay(KST).toInstant();
        Long draftId = insertDraft(
                midnight.minusSeconds(3600), midnight.plusSeconds(600), staleLastSeen(), 4200, 4000, "[]");

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(2);
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 신선한_draft는_확정_대상이_아니다() {
        insertDraft(startedAt, startedAt.plusSeconds(60), Instant.now(), 60, 60, "[]");

        assertThat(activeStudySessionService.findStaleDraftIds()).isEmpty();
    }

    @Test
    void 이미_클라가_제출한_세션의_잔여_draft는_세션을_건드리지_않고_삭제만_된다() {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, false)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(startedAt, KST)),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(3600)),
                3600,
                3400);
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, "[]");

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(1);
        Integer studySec = jdbcTemplate.queryForObject(
                "SELECT study_sec FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySec).isEqualTo(3600); // 클라 제출본 불가침
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 자동_확정본이_있는_세션의_draft가_재확정되면_더_완전한_기록으로_대체된다() {
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec, auto_finalized) VALUES (?, ?, ?, ?, ?, ?, ?, true)",
                userId,
                java.sql.Date.valueOf(LocalDate.ofInstant(startedAt, KST)),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt),
                java.sql.Timestamp.from(startedAt.plusSeconds(1800)),
                1800,
                1700);
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(3600), staleLastSeen(), 3600, 3400, "[]");

        activeStudySessionService.finalizeDraft(draftId);

        assertThat(sessionRows()).isEqualTo(1);
        Integer studySec = jdbcTemplate.queryForObject(
                "SELECT study_sec FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySec).isEqualTo(3600);
        assertThat(draftRows()).isEqualTo(0);
    }

    @Test
    void 이미_처리된_draftId는_조용히_넘어간다() {
        activeStudySessionService.finalizeDraft(999_999L);
        assertThat(sessionRows()).isEqualTo(0);
    }

    @Test
    void discardDraft는_세션_없이_draft만_지운다() {
        Long draftId = insertDraft(startedAt, startedAt.plusSeconds(1800), staleLastSeen(), 1800, 1700, "[]");

        activeStudySessionService.discardDraft(draftId);

        assertThat(sessionRows()).isEqualTo(0);
        assertThat(draftRows()).isEqualTo(0);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionFinalizeTest"`
Expected: 컴파일 실패 (`findStaleDraftIds`/`finalizeDraft`/`discardDraft` 없음)

- [ ] **Step 3: 서비스에 확정 메서드 추가**

`ActiveStudySessionService.java`에 추가 (import: `java.time.Duration`, `org.springframework.transaction.annotation.Transactional`, `project.study.studysession.dto.StudySessionCreateRequest`, `project.study.studysession.entity.ActiveStudySession`, `tools.jackson.core.type.TypeReference`):

```java
    /** 이 시간 넘게 하트비트가 없으면 죽었다고 본다 — 30초 주기 기준 10회 연속 유실. 판정은 서버 시계(lastSeenAt). */
    static final Duration FINALIZE_GRACE = Duration.ofMinutes(5);

    @Transactional(readOnly = true)
    public List<Long> findStaleDraftIds() {
        return activeStudySessionRepository.findByLastSeenAtBefore(clock.instant().minus(FINALIZE_GRACE)).stream()
                .map(ActiveStudySession::getId)
                .toList();
    }

    /**
     * draft를 세션으로 확정한다 — 검증·자정 분할·대체 정책은 전부 create(autoFinalized=true) 재사용.
     * 일부러 트랜잭션을 걸지 않는다: create가 자체 트랜잭션으로 돌아야, 유니크 충돌로 create가
     * 롤백돼도(rollback-only 오염) 후속 draft 정리가 새 트랜잭션에서 살아남는다 — 컨트롤러가
     * DuplicateSessionException 후 findExistingSubmission을 새 트랜잭션으로 부르는 것과 같은 이유다.
     * 세션 저장과 draft 삭제의 원자성은 create 안에서 보장된다(성공 시 create가 draft도 지운다).
     */
    public void finalizeDraft(Long draftId) {
        ActiveStudySession draft =
                activeStudySessionRepository.findById(draftId).orElse(null);
        if (draft == null) {
            return; // 최종 제출이 먼저 처리해 이미 삭제됨
        }
        List<StatusEventRequest> events =
                objectMapper.readValue(draft.getEvents(), new TypeReference<List<StatusEventRequest>>() {});
        StudySessionCreateRequest request = new StudySessionCreateRequest(
                draft.getUserId(),
                draft.getStartedAt(),
                draft.getReportedAt(),
                draft.getStudySec(),
                draft.getFocusSec(),
                events);
        try {
            studySessionService.create(draft.getUserId(), request, true);
        } catch (DuplicateSessionException e) {
            // 별개 제출의 분할 조각과 시각이 충돌 — 기록이 이미 있으니 아래에서 draft만 정리한다
        }
        // create 성공 경로는 이미 트랜잭션 안에서 draft를 지웠으므로 no-op이고,
        // 멱등 반환(클라 제출본 존재)·중복 충돌 경로에서만 실제로 지운다 (deleteById는 없으면 무시)
        activeStudySessionRepository.deleteById(draftId);
    }

    /**
     * 확정이 불가능한 draft를 폐기한다 — finalizeDraft의 create가 검증 예외로 실패한 뒤 스케줄러가
     * 호출한다. 하트비트 검증이 막았어야 할 데이터라 재시도해도 영원히 실패한다.
     */
    @Transactional
    public void discardDraft(Long draftId) {
        activeStudySessionRepository.deleteById(draftId);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionFinalizeTest"`
Expected: PASS (7개)

- [ ] **Step 5: 스케줄러 작성**

`src/main/java/project/study/studysession/scheduler/ActiveSessionFinalizeScheduler.java`:

```java
package project.study.studysession.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.studysession.service.ActiveStudySessionService;

/**
 * 하트비트가 끊긴 진행중 세션(draft)을 자동 확정한다 (BY-447, ADR-0014).
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
 * 로그만 남기고 삼켜서 Sentry에 아무것도 남지 않는다 (DailyReportScheduler와 동일).
 *
 * <p>테스트에서는 session-finalize.enabled=false로 꺼서 백그라운드 확정이 테스트 데이터를
 * 건드리지 않게 한다 — 확정 로직 자체는 서비스 메서드 직접 호출로 검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "session-finalize.enabled", havingValue = "true", matchIfMissing = true)
public class ActiveSessionFinalizeScheduler {

    private final ActiveStudySessionService activeStudySessionService;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void finalizeStaleDrafts() {
        for (Long draftId : activeStudySessionService.findStaleDraftIds()) {
            try {
                activeStudySessionService.finalizeDraft(draftId);
            } catch (Exception e) {
                log.error("진행중 세션 자동 확정 실패 — draft를 폐기한다: draftId={}", draftId, e);
                Sentry.captureException(e);
                activeStudySessionService.discardDraft(draftId);
            }
        }
    }
}
```

- [ ] **Step 6: 테스트 설정에 스케줄러 비활성 추가**

`src/test/resources/application.yaml` 끝에 추가:

```yaml
# BY-447 확정 스케줄러 — 테스트에서는 끈다 (백그라운드 확정이 테스트 데이터를 건드리는 경합 방지)
session-finalize:
  enabled: false
```

- [ ] **Step 7: 전체 검증 후 커밋**

```bash
./gradlew check
git add src/main/java/project/study/studysession/service/ActiveStudySessionService.java \
    src/main/java/project/study/studysession/scheduler/ActiveSessionFinalizeScheduler.java \
    src/test/resources/application.yaml \
    src/test/java/project/study/studysession/ActiveSessionFinalizeTest.java
git commit -m "feat: 무응답 세션 자동 확정 스케줄러 추가 (BY-447)"
```

---

### Task 5: 문서 정리 — ADR-0014 + 낡은 Swagger 문구 수정

코드 변경 없는 문서 작업 두 가지. ADR-0014는 ADR-0003 부분 대체를 기록하고, `StudySessionController`의 Swagger에서 ADR-0009로 삭제된 최소 10분 규칙 잔재를 지우면서 대체 정책 설명을 더한다.

**Files:**
- Create: `docs/adr/0014-active-session-snapshot-auto-finalize.md`
- Modify: `src/main/java/project/study/studysession/controller/StudySessionController.java`
- Modify: `docs/adr/0003-client-driven-study-session.md` (상태 줄에 부분 대체 표기)

**Interfaces:** 없음 (문서만)

- [ ] **Step 1: ADR-0014 작성**

`docs/adr/0014-active-session-snapshot-auto-finalize.md`:

```markdown
# ADR-0014: 진행중 세션 스냅샷과 서버 자동 확정

- 상태: 승인 (ADR-0003의 "서버에 미종료 세션 개념을 두지 않는다" 결정을 부분 대체)
- 날짜: 2026-08-27

## 맥락

세션은 퇴장 시 1회 일괄 제출된다(ADR-0003). 클라이언트가 제출 없이 죽으면
세션이 유실되고, ADR-0003이 위임한 로컬 백업 복구는 저장소 소실·앱 삭제·영영
미복귀에는 무력하다. 서버 측 복구 경로가 필요해졌다 — 솔로·소셜 공통.

## 결정

공부 중 클라이언트가 30초마다 진행 스냅샷을 `PUT /api/study-sessions/active`로
보고하고, 서버는 보고가 5분 넘게 끊긴 세션을 마지막 스냅샷 기준으로 자동 확정한다.

- **별도 draft 테이블**(`active_study_session`, 세션당 1행, 이벤트는 jsonb):
  `study_session`의 "행 = 확정 세션" 불변식을 유지한다. 같은 테이블에 상태
  컬럼을 두는 안은 모든 조회에 필터가 스며들고 확정이 자정 분할 탓에 결국 행
  갈아끼우기가 되어 기각했다.
- **누적 스냅샷**: 매 보고가 지금까지의 전체를 담아 통째로 덮어쓴다. 일부
  유실·역순 도착에도 마지막 스냅샷만 살아남으면 되므로 재동기화가 필요 없다.
- **두 시계의 분리**: 확정 시 종료 시각이 되는 `reported_at`은 클라 시계
  (ADR-0003 신뢰 모델 — 서버 시계를 섞으면 이벤트가 세션 밖에 놓이는 모순이
  생긴다), 무응답 판정용 `last_seen_at`은 서버 시계(클라 시계가 느린 유저를
  공부 중에 확정해버리지 않도록).
- **확정은 기존 create 재사용**: 검증·자정 분할·statDate 계산·멱등을 그대로
  쓴다. 하트비트 검증도 같은 코드(createSessions)를 호출해 결과만 버리므로
  draft는 항상 확정 가능하다. 길이 무관 전부 확정한다(ADR-0009).
- **자동 확정본은 잠정 기록**(`auto_finalized` 컬럼): 같은 제출 키의 새 도착분
  (늦은 최종 제출·재확정)이 오면 무조건 대체한다. 클라 제출본은 불가침.
  길이 비교는 하지 않는다 — 스냅샷이 누적값이라 나중 도착분이 항상 상위집합이고,
  시계 역행으로 짧아지는 극단 케이스는 ADR-0003 신뢰 모델의 일부로 수용한다.
- **STOMP 하트비트(10초)와 무관**: WS 끊김은 세션을 확정시키지 않고 그 역도
  없다. 룸 이력과 세션 기록의 종료 시각이 1분 남짓 어긋날 수 있으며, 공부
  시간의 원천은 항상 세션 쪽이다.

## 결과

- 미제출 세션의 최악 유실이 마지막 스냅샷 이후 30초로 준다
- 유예(5분) 오탐으로 공부 중 세션이 확정돼도 대체 정책이 더 완전한 기록으로
  수렴시키므로 자가 치유된다
- 서버에 진행중 상태가 생겼지만 draft 테이블에 격리된다 — 통계·스트릭·조회
  코드는 변경 없음
- 유저 1명당 시간당 120회 쓰기가 추가되나 세션당 1행 UPSERT라 부하는 동시
  세션 수에 비례하는 수준이다
```

- [ ] **Step 2: ADR-0003 상태 줄 갱신**

`docs/adr/0003-client-driven-study-session.md`의 상태 줄을:

```markdown
- 상태: 승인 (focus_sec 서버 계산 부분은 ADR-0006으로 대체, "미종료 세션 없음" 부분은 ADR-0014로 대체)
```

- [ ] **Step 3: StudySessionController Swagger 정리**

`StudySessionController.java`의 `@Operation` description에서:

1. 검증 규칙 목록의 아래 줄 삭제 (ADR-0009로 폐기된 규칙):
```
- 세션은 10분 이상이어야 한다 — 10분 미만 세션은 저장되지 않고 `400`으로 거절된다 \
(저장되지 않으므로 스트릭·통계에도 잡히지 않는다)
```
2. 400 응답의 `@ExampleObject(name = "10분 미만", value = "{\"message\": \"세션은 10분 이상이어야 합니다\"}")` 삭제
3. "멱등 재제출" 문단 끝에 추가:
```
자동 확정본(잠정 기록)이 이미 저장돼 있으면 재제출이 아니라 대체가 일어난다 — 진행중 세션 스냅샷 API 참고 (ADR-0014).
```

- [ ] **Step 4: 전체 검증 후 커밋**

Run: `./gradlew check`
Expected: PASS (Swagger는 문자열이라 컴파일 영향 없음 — Spotless 포맷만 확인)

```bash
git add docs/adr/0014-active-session-snapshot-auto-finalize.md \
    docs/adr/0003-client-driven-study-session.md \
    src/main/java/project/study/studysession/controller/StudySessionController.java
git commit -m "docs: ADR-0014 자동 확정 기록·낡은 10분 규칙 문구 정리 (BY-447)"
```

---

## 완료 후

1. `./gradlew check` 최종 통과 확인
2. CLAUDE.md 규칙: 커밋 전 **퀴즈 게이트**(구현 코드·흐름 퀴즈 5개)와 `/codex review` 2차 리뷰는 태스크별 커밋이 아니라 **PR 올리기 전** 시점에 수행한다
3. PR: `[feat] BY-447 진행중 세션 스냅샷 보고·자동 확정` — base는 `dev`
```
