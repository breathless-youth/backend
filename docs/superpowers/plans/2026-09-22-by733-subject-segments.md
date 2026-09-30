# BY-733 과목 시간을 구간 계약으로 교체 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 세션 제출·스냅샷·복구가 과목별 합계(`subjectTimes`) 대신 과목 전환 구간(`subjectSegments`)을 받고, 서버가 구간과 비공부 이벤트로 과목별 순공·총공부를 계산해 저장한다.

**Architecture:** `study_session_subject_time`(합계 행)을 `study_session_subject_segment`(구간 행 + 서버 파생값)로 교체한다. 검증은 이벤트와 같은 규칙(`StudySessionValidator`), 자정 절단·파생 계산은 새 순수 클래스 `SubjectSegmentSplitter`가 맡고 `StudySessionSplitter.buildSessions`가 조각마다 부른다. 스냅샷 jsonb 컬럼도 `subject_segments`로 바뀌고, 과목 누적은 새 테이블을 `subject_id`로 합산한다. 소유 검증은 지금처럼 컨트롤러가 `StudySubjectService.assertOwned`를 먼저 부른다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL 17(Testcontainers 2.0), Flyway, Hibernate(`ddl-auto=validate`), MockMvcTester, JUnit 5 + Mockito, Spotless(palantir), Checkstyle(FileLength 400·ParameterNumber 7), ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-22-by556-record-tab-v2-design.md` §4 (BY-733). §5(BY-734)·§6(BY-735)은 이 계획 범위 밖.

## Global Constraints

- 브랜치 `feature/BY-733-subject-segments` (origin/dev `01ef441` 기준). 다음 마이그레이션 번호 **V20** — 분기 직후 `ls src/main/resources/db/migration | tail -2`로 재확인했다(V19가 마지막).
- 병행 없음: `subjectTimes`는 배포된 앱이 보내지 않는다. 구 앱(API-Version 1) 경로는 새 필드를 몰라 영향 없다 — `Legacy*Request.toRequest()`는 6번째 인자에 `null`을 넘기는 코드 그대로다.
- 계약 필드 위치: `StudySessionCreateRequest`의 6번째 컴포넌트, `ActiveSessionSnapshotRequest`의 6번째 컴포넌트를 **같은 자리에서** 교체한다 — 7인자·6인자 생성자를 쓰는 기존 테스트(`StudySessionControllerRetryTest`, `StudySessionIdempotencyServiceTest`)와 시더가 `null`을 넘겨 그대로 컴파일된다.
- 세션 단위 `studySec`·`focusSec`는 앱 값 그대로. 구간 합을 세션 값에 맞추는 검증은 **두지 않는다**(ADR-0021 §4).
- 검증 위반은 `InvalidSessionException`(400). 메시지는 이벤트 메시지와 같은 어투("…이어야 합니다", "…할 수 없습니다").
- 커밋은 `./gradlew check` 통과 상태에서만. 커밋 메시지는 Conventional Commits + 본문 "왜" + `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- 기능 커밋 전 **퀴즈 게이트**(5문제)와 **Codex 2차 리뷰**(`codex exec -s read-only "<프롬프트>"`, P1이면 FAIL) — Task 1 Step 끝에 있다.
- 세션 도메인은 과목 엔티티를 모른다(ADR-0021 §6). 세션 서비스에 과목 의존성을 넣지 않는다.
- Bash 우선(bypass 모드): 파일 읽기 `sed -n`, 수정은 Edit 도구가 안전한 경우에만 Edit.

---

## 파일 구조

**생성**
- `src/main/resources/db/migration/V20__study_session_subject_segment.sql` — 테이블 교체 + 스냅샷 컬럼 교체
- `src/main/java/project/study/studysession/entity/StudySessionSubjectSegment.java` — 구간 행 엔티티(파생값 포함)
- `src/main/java/project/study/studysession/repository/StudySessionSubjectSegmentRepository.java` — 과목 누적 합산
- `src/main/java/project/study/studysession/dto/SubjectSegmentRequest.java` — 제출·스냅샷·복구 공용 요청 원소
- `src/main/java/project/study/studysession/dto/SubjectSegmentResponse.java` — 응답 원소
- `src/main/java/project/study/studysession/service/SubjectSegmentSplitter.java` — 절단·파생 계산 순수 로직
- `src/test/java/project/study/studysession/service/SubjectSegmentSplitTest.java` — 단위테스트(검증·절단·계산)
- `src/test/java/project/study/studysession/StudySessionSubjectSegmentApiTest.java` — API 테스트(제출·스냅샷·복구·자동확정·누적)
- `docs/adr/0023-subject-segments-server-derived.md`

**삭제**
- `entity/StudySessionSubjectTime.java`, `repository/StudySessionSubjectTimeRepository.java`, `dto/SubjectTimeRequest.java`, `dto/SubjectTimeResponse.java`
- `src/test/java/project/study/studysession/service/SubjectTimeSplitTest.java`, `src/test/java/project/study/studysession/StudySessionSubjectTimeApiTest.java`

**수정**
- `entity/StudySession.java` — 컬렉션 교체
- `entity/ActiveStudySession.java` — jsonb 컬럼 교체
- `dto/StudySessionCreateRequest.java`, `dto/ActiveSessionSnapshotRequest.java`, `dto/ActiveSessionSnapshotResponse.java`, `dto/StudySessionResponse.java`
- `service/StudySessionValidator.java`, `service/StudySessionSplitter.java`, `service/SessionAttachments.java`, `service/StudySessionService.java`, `service/ActiveStudySessionService.java`
- `buffer/ActiveSnapshotBuffer.java`, `repository/ActiveStudySessionBatchRepository.java`
- `controller/StudySessionController.java`, `controller/ActiveStudySessionController.java`
- `subject/service/StudySubjectService.java`, `subject/controller/StudySubjectController.java`
- `config/DevDataSeeder.java`
- `src/test/java/project/study/subject/service/StudySubjectServiceTest.java`, `src/test/java/project/study/studysession/StudySessionControllerRetryTest.java`(주석)
- `docs/erd.dbml`, `docs/adr/0021-subject-task-session-time.md`(갱신 표시)

---

### Task 1: 구간 계약 교체 — 스키마·엔티티·검증·절단·계산·스냅샷·누적

한 커밋이다. V20이 옛 테이블과 스냅샷 컬럼을 지우므로(`ddl-auto=validate`) 옛 엔티티·DTO를 참조하는 모든 코드가 같은 커밋에서 바뀌어야 컨텍스트가 뜬다. 단위테스트 → 컴파일 실패 확인 → 구현 → API 테스트 순으로 간다.

**Files:**
- Create: V20, `StudySessionSubjectSegment`, `StudySessionSubjectSegmentRepository`, `SubjectSegmentRequest`, `SubjectSegmentResponse`, `SubjectSegmentSplitter`, `SubjectSegmentSplitTest`, `StudySessionSubjectSegmentApiTest`
- Delete: `StudySessionSubjectTime`, `StudySessionSubjectTimeRepository`, `SubjectTimeRequest`, `SubjectTimeResponse`, `SubjectTimeSplitTest`, `StudySessionSubjectTimeApiTest`
- Modify: 위 "수정" 목록 중 `DevDataSeeder`·docs 제외 전부

**Interfaces:**
- Produces: `SubjectSegmentRequest(Long subjectId, Instant startedAt, Instant endedAt)`, `SubjectSegmentResponse(Long subjectId, Instant startedAt, Instant endedAt, Integer studySec, Integer focusSec)`, `StudySessionSubjectSegment(Long subjectId, Instant startedAt, Instant endedAt, int studySec, int focusSec)`, `StudySessionCreateRequest.subjectSegmentsOrEmpty()`·`subjectIds()`, `ActiveSessionSnapshotRequest.subjectSegmentsOrEmpty()`·`subjectIds()`, `StudySubjectService.assertOwned(Long userId, Collection<Long> subjectIds)`, `StudySessionService.validateAndBuildSessions(Long, Instant, Instant, int, int, List<StatusEvent>, List<SubjectSegmentRequest>)`, `SubjectSegmentSplitter.clip(List<SubjectSegmentRequest> sorted, List<StatusEvent> pieceEvents, Instant pieceStart, Instant pieceEnd) → List<StudySessionSubjectSegment>`.

- [ ] **Step 1: 단위테스트 작성 — `SubjectSegmentSplitTest`**

`src/test/java/project/study/studysession/service/SubjectTimeSplitTest.java`를 삭제하고 아래를 만든다.

```java
package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSubjectSegment;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/** 과목 구간의 검증·자정 절단·파생 계산 (ADR-0023) — 이벤트 겹침이 총공부·순공에 정확히 빠지는지, 절단 뒤 합이 보존되는지. */
@ExtendWith(MockitoExtension.class)
class SubjectSegmentSplitTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00)
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    // KST 23일 23:00 ~ 24일 01:00 (자정 경계 = 2026-07-23T15:00:00Z)
    private static final Instant CROSS_START = Instant.parse("2026-07-23T14:00:00Z");
    private static final Instant CROSS_END = Instant.parse("2026-07-23T16:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-07-23T15:00:00Z");

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    private static SubjectSegmentRequest seg(long subjectId, String start, String end) {
        return new SubjectSegmentRequest(subjectId, Instant.parse(start), Instant.parse(end));
    }

    private static StatusEvent event(EventStatus status, String start, String end) {
        return new StatusEvent(status, Instant.parse(start), Instant.parse(end));
    }

    @Test
    void 이벤트가_없으면_구간_길이가_곧_총공부이자_순공이고_시작_시각_순으로_정렬된다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                START,
                END,
                7200,
                7200,
                List.of(),
                List.of(seg(2, "2026-07-24T09:00:00Z", "2026-07-24T09:30:00Z"), seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z")));

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSubjectSegments())
                .extracting(
                        StudySessionSubjectSegment::getSubjectId,
                        StudySessionSubjectSegment::getStudySec,
                        StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(1L, 3600, 3600), tuple(2L, 1800, 1800));
    }

    @Test
    void 구간이_없으면_기존과_같이_저장된다() {
        List<StudySession> sessions = service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of());

        assertThat(sessions.get(0).getSubjectSegments()).isEmpty();
    }

    @Test
    void 일시정지_겹침은_총공부와_순공에서_모두_빠지고_다른_이벤트_겹침은_순공에서만_빠진다() {
        // 구간 08:00~09:00. PAUSE 08:10~08:20(10분), PHONE 08:40~08:45(5분), 구간 밖 AWAY 09:30~09:40
        List<StatusEvent> events = List.of(
                event(EventStatus.PAUSE, "2026-07-24T08:10:00Z", "2026-07-24T08:20:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:40:00Z", "2026-07-24T08:45:00Z"),
                event(EventStatus.AWAY, "2026-07-24T09:30:00Z", "2026-07-24T09:40:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 6600, 5700, events, List.of(seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z")));

        StudySessionSubjectSegment segment = sessions.get(0).getSubjectSegments().get(0);
        assertThat(segment.getStudySec()).isEqualTo(3600 - 600);
        assertThat(segment.getFocusSec()).isEqualTo(3600 - 600 - 300);
    }

    @Test
    void 이벤트가_구간_경계에_걸치면_겹친_만큼만_뺀다() {
        // 구간 08:30~09:00, PHONE 08:20~08:40 → 겹침 10분
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:20:00Z", "2026-07-24T08:40:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 7200, 6000, events, List.of(seg(1, "2026-07-24T08:30:00Z", "2026-07-24T09:00:00Z")));

        StudySessionSubjectSegment segment = sessions.get(0).getSubjectSegments().get(0);
        assertThat(segment.getStudySec()).isEqualTo(1800);
        assertThat(segment.getFocusSec()).isEqualTo(1800 - 600);
    }

    @Test
    void 이벤트가_구간을_완전히_덮으면_0초_행이_남는다() {
        List<StatusEvent> events = List.of(event(EventStatus.PAUSE, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 3600, 3600, events, List.of(seg(1, "2026-07-24T08:10:00Z", "2026-07-24T08:50:00Z")));

        assertThat(sessions.get(0).getSubjectSegments())
                .extracting(StudySessionSubjectSegment::getStudySec, StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(0, 0));
    }

    @Test
    void 자정을_넘으면_구간이_자정에서_잘리고_조각마다_그_조각의_이벤트로_계산된다() {
        // 구간 23:30~00:30(KST). 첫 조각에만 PHONE 23:40~23:50(10분)
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-23T14:40:00Z", "2026-07-23T14:50:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, CROSS_START, CROSS_END, 7200, 6600, events, List.of(seg(1, "2026-07-23T14:30:00Z", "2026-07-23T15:30:00Z")));

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getSubjectSegments())
                .extracting(
                        StudySessionSubjectSegment::getStartedAt,
                        StudySessionSubjectSegment::getEndedAt,
                        StudySessionSubjectSegment::getStudySec,
                        StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(Instant.parse("2026-07-23T14:30:00Z"), MIDNIGHT, 1800, 1200));
        assertThat(sessions.get(1).getSubjectSegments())
                .extracting(
                        StudySessionSubjectSegment::getStartedAt,
                        StudySessionSubjectSegment::getEndedAt,
                        StudySessionSubjectSegment::getStudySec,
                        StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(MIDNIGHT, Instant.parse("2026-07-23T15:30:00Z"), 1800, 1800));
    }

    @Test
    void 정확히_자정에_끝나는_구간은_첫_조각에만_남고_둘째_조각에는_0초_조각을_만들지_않는다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, CROSS_START, CROSS_END, 7200, 7200, List.of(), List.of(seg(1, "2026-07-23T14:00:00Z", "2026-07-23T15:00:00Z")));

        assertThat(sessions.get(0).getSubjectSegments()).hasSize(1);
        assertThat(sessions.get(1).getSubjectSegments()).isEmpty();
    }

    @Test
    void 구간이_서로_겹치면_거절한다() {
        List<SubjectSegmentRequest> overlapping = List.of(
                seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z"), seg(2, "2026-07-24T08:59:00Z", "2026-07-24T09:30:00Z"));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), overlapping))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("과목 구간이 서로 겹칠 수 없습니다");
    }

    @Test
    void 맞닿는_구간은_허용한다() {
        List<SubjectSegmentRequest> touching = List.of(
                seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z"), seg(2, "2026-07-24T09:00:00Z", "2026-07-24T09:30:00Z"));

        assertThat(service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), touching)
                        .get(0)
                        .getSubjectSegments())
                .hasSize(2);
    }

    @Test
    void 구간이_세션_밖이면_거절한다() {
        List<SubjectSegmentRequest> outside = List.of(seg(1, "2026-07-24T07:50:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), outside))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("과목 구간은 세션 구간 안에 있어야 합니다");
    }

    @Test
    void 종료가_시작_이후가_아닌_구간은_거절한다() {
        List<SubjectSegmentRequest> zero = List.of(seg(1, "2026-07-24T08:30:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), zero))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("과목 구간 종료 시각은 시작 시각 이후여야 합니다");
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava -q 2>&1 | head -20`
Expected: `SubjectSegmentRequest`·`StudySessionSubjectSegment`·`getSubjectSegments` 심볼을 찾을 수 없다는 컴파일 오류.

- [ ] **Step 3: V20 마이그레이션**

`src/main/resources/db/migration/V20__study_session_subject_segment.sql`:

```sql
-- BY-733: 과목 시간을 합계가 아니라 구간으로 받는다 (ADR-0023)
-- 앱은 과목 전환 시각(subjectSegments)만 보내고, 과목별 총공부·순공은 서버가 구간과 비공부 이벤트로 계산해 행에 같이 둔다
-- (과목 누적이 subject_id 합산 한 문장이 되도록). 과목 시트 앱이 배포 전이라 합계 행은 이관 없이 버린다.
DROP TABLE study_session_subject_time;

CREATE TABLE study_session_subject_segment (
    id         BIGSERIAL   PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    subject_id BIGINT      NOT NULL REFERENCES study_subject (id),
    started_at TIMESTAMPTZ NOT NULL,   -- 자정 분할 조각에는 잘린 구간이 담긴다
    ended_at   TIMESTAMPTZ NOT NULL,
    study_sec  INT         NOT NULL,   -- 서버 파생값: 길이 − PAUSE 겹침
    focus_sec  INT         NOT NULL    -- 서버 파생값: 길이 − 모든 이벤트 겹침
);
CREATE INDEX idx_study_session_subject_segment_session ON study_session_subject_segment (session_id);
CREATE INDEX idx_study_session_subject_segment_subject ON study_session_subject_segment (subject_id);

-- 진행중 스냅샷도 구간을 보관한다 — 옛 합계 JSON은 모양이 달라 버린다(배포 전)
ALTER TABLE active_study_session DROP COLUMN subject_times;
ALTER TABLE active_study_session ADD COLUMN subject_segments JSONB NOT NULL DEFAULT '[]';
```

- [ ] **Step 4: 엔티티·리포지토리 교체**

삭제: `src/main/java/project/study/studysession/entity/StudySessionSubjectTime.java`, `src/main/java/project/study/studysession/repository/StudySessionSubjectTimeRepository.java`.

생성 `src/main/java/project/study/studysession/entity/StudySessionSubjectSegment.java`:

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

/**
 * 세션 안에서 과목을 선택한 채 공부한 구간 [startedAt, endedAt) (ADR-0023). 세션의 자식 컬렉션이라 StatusEvent처럼
 * 세션과 함께 저장·삭제되고, 자정 분할 조각마다 잘린 행이 따로 생긴다.
 *
 * <p>studySec·focusSec는 서버가 구간과 이벤트로 계산한 파생값이다 — 앱 타이머가 멈추는 규칙과 같이
 * PAUSE 겹침은 둘 다에서, 다른 이벤트 겹침은 순공에서만 빠진다. 과목 누적이 subject_id 합산 한 문장이 되도록 행에 둔다.
 */
@Entity
@Table(name = "study_session_subject_segment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySessionSubjectSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "study_sec", nullable = false)
    private Integer studySec;

    @Column(name = "focus_sec", nullable = false)
    private Integer focusSec;

    public StudySessionSubjectSegment(Long subjectId, Instant startedAt, Instant endedAt, int studySec, int focusSec) {
        this.subjectId = subjectId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.studySec = studySec;
        this.focusSec = focusSec;
    }
}
```

생성 `src/main/java/project/study/studysession/repository/StudySessionSubjectSegmentRepository.java`:

```java
package project.study.studysession.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.entity.StudySessionSubjectSegment;

/** 과목 누적 시간 집계 — 저장은 StudySession의 cascade가 하고, 여기서는 구간 행의 파생값을 합산만 한다 (ADR-0023). */
public interface StudySessionSubjectSegmentRepository extends JpaRepository<StudySessionSubjectSegment, Long> {

    // 과목 누적 — 그 과목 구간 전부의 총공부·순공 합
    @Query("""
            select new project.study.studysession.dto.SubjectTimeSum(s.subjectId, sum(s.studySec), sum(s.focusSec))
            from StudySessionSubjectSegment s
            where s.subjectId in :subjectIds
            group by s.subjectId""")
    List<SubjectTimeSum> sumBySubjectIds(@Param("subjectIds") Collection<Long> subjectIds);
}
```

`src/main/java/project/study/studysession/entity/StudySession.java` — 컬렉션과 attach 메서드를 교체한다. import `StudySessionSubjectTime` 참조는 없다(같은 패키지).

```java
    // 과목 구간 (ADR-0023) — 이벤트와 같은 자식 컬렉션. 자정 분할 조각마다 잘린 행이 생기고 파생값은 서버가 계산한다
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "session_id", nullable = false)
    @OrderBy("startedAt ASC")
    private List<StudySessionSubjectSegment> subjectSegments = new ArrayList<>();
```

```java
    /** 조각별로 잘라 계산한 과목 구간을 붙인다 — 분할 직후 서비스만 호출한다. */
    public void attachSubjectSegments(List<StudySessionSubjectSegment> subjectSegments) {
        this.subjectSegments = new ArrayList<>(subjectSegments);
    }
```

(기존 `private List<StudySessionSubjectTime> subjectTimes` 필드와 `attachSubjectTimes`는 지운다.)

- [ ] **Step 5: DTO 교체**

삭제: `dto/SubjectTimeRequest.java`, `dto/SubjectTimeResponse.java`.

생성 `src/main/java/project/study/studysession/dto/SubjectSegmentRequest.java`:

```java
package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 세션 제출·스냅샷·복구에 공통으로 실리는 과목 구간 1건 — 과목을 선택한 채 공부한 [startedAt, endedAt) (ADR-0023).
 * 길이와 과목별 총공부·순공은 서버가 비공부 이벤트와 겹쳐 계산하므로 보내지 않는다 — 이벤트가 시각만 보내는 것과 같은 원칙이다.
 */
public record SubjectSegmentRequest(
        @Schema(description = "과목 ID — 토큰 유저의 과목이어야 한다. 세션 중 지운 과목도 허용된다(기록은 남긴다)", example = "3") @NotNull
        Long subjectId,
        @Schema(description = "구간 시작 시각 (UTC, ISO-8601) — 세션 구간 안이어야 한다", example = "2026-09-22T00:12:00Z") @NotNull
        Instant startedAt,
        @Schema(
                description = "구간 종료 시각 (UTC, ISO-8601) — 시작 이후여야 하고, 다른 구간과 겹칠 수 없다(끝과 시작이 맞닿는 것은 허용). "
                        + "진행 중인 구간은 스냅샷의 reportedAt(최종 제출은 endedAt)에서 닫아서 보낸다",
                example = "2026-09-22T00:41:00Z")
        @NotNull
        Instant endedAt) {}
```

생성 `src/main/java/project/study/studysession/dto/SubjectSegmentResponse.java`:

```java
package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import project.study.studysession.entity.StudySessionSubjectSegment;

/** 저장된 세션의 과목 구간 1건 — 자정 분할 조각에는 잘린 구간이, 파생값에는 그 조각 이벤트로 계산한 값이 담긴다 (ADR-0023). */
public record SubjectSegmentResponse(
        @Schema(description = "과목 ID", example = "3") Long subjectId,
        @Schema(description = "구간 시작 시각 (UTC, ISO-8601)", example = "2026-09-22T00:12:00Z")
        Instant startedAt,
        @Schema(description = "구간 종료 시각 (UTC, ISO-8601)", example = "2026-09-22T00:41:00Z")
        Instant endedAt,
        @Schema(description = "이 구간의 총 공부 시간(초) — 서버 계산: 구간 길이 − PAUSE 겹침", example = "1740")
        Integer studySec,
        @Schema(description = "이 구간의 순공 시간(초) — 서버 계산: 구간 길이 − 모든 비공부 이벤트 겹침", example = "1620")
        Integer focusSec) {
    public static SubjectSegmentResponse from(StudySessionSubjectSegment segment) {
        return new SubjectSegmentResponse(
                segment.getSubjectId(),
                segment.getStartedAt(),
                segment.getEndedAt(),
                segment.getStudySec(),
                segment.getFocusSec());
    }
}
```

`src/main/java/project/study/studysession/dto/StudySessionCreateRequest.java` — 6번째 컴포넌트와 헬퍼를 교체한다. import에서 `StudySessionSubjectTime`을 지우고 `java.util.Set`·`java.util.stream.Collectors`를 추가한다.

```java
        @Schema(
                description = "과목 구간 목록 — 과목을 선택한 채 공부한 [startedAt, endedAt) (ADR-0023). 선택 필드라 없거나 []이면 "
                        + "기존과 동일하게 저장된다. 구간은 세션 안에 있고 서로 겹치지 않아야 하며(맞닿음 허용, 순서 무관) subjectId는 "
                        + "토큰 유저의 과목이어야 한다(위반 400). 구간 사이 빈 시간은 과목 미선택이다. 과목별 총공부·순공은 서버가 "
                        + "비공부 이벤트와 겹쳐 계산해 응답 subjectSegments에 싣는다. 자정을 넘는 세션은 구간도 자정에서 잘린다")
        @Valid
        List<SubjectSegmentRequest> subjectSegments,
```

헬퍼(기존 `subjectTimesOrEmpty`·`getSubjectTimeList` 대체):

```java
    /** 선택 필드라 null이면 빈 목록으로 다룬다. */
    public List<SubjectSegmentRequest> subjectSegmentsOrEmpty() {
        return subjectSegments == null ? List.of() : subjectSegments;
    }

    /** 소유 검증용 과목 id 집합 — 없으면 빈 집합. */
    public Set<Long> subjectIds() {
        return subjectSegmentsOrEmpty().stream().map(SubjectSegmentRequest::subjectId).collect(Collectors.toSet());
    }
```

`src/main/java/project/study/studysession/dto/ActiveSessionSnapshotRequest.java` — 같은 방식. 6번째 컴포넌트:

```java
        @Schema(
                description = "지금까지의 과목 구간 전체 — 최종 제출의 subjectSegments와 같은 규칙(reportedAt을 세션 끝으로 본다). "
                        + "진행 중인 구간은 reportedAt에서 닫아서 보낸다. 선택 필드라 없거나 []이면 기존과 동일")
        @Valid
        List<SubjectSegmentRequest> subjectSegments) {
    /** 선택 필드라 null이면 빈 목록으로 다룬다. */
    public List<SubjectSegmentRequest> subjectSegmentsOrEmpty() {
        return subjectSegments == null ? List.of() : subjectSegments;
    }

    /** 소유 검증용 과목 id 집합 — 없으면 빈 집합. */
    public Set<Long> subjectIds() {
        return subjectSegmentsOrEmpty().stream().map(SubjectSegmentRequest::subjectId).collect(Collectors.toSet());
    }
}
```

`src/main/java/project/study/studysession/dto/ActiveSessionSnapshotResponse.java` — 마지막 컴포넌트:

```java
        @Schema(description = "마지막 스냅샷까지의 과목 구간 — 보고 요청과 같은 모양이라 마지막 구간의 과목으로 선택 상태를 복원한다. 없으면 []")
        List<SubjectSegmentRequest> subjectSegments) {}
```

`src/main/java/project/study/studysession/dto/StudySessionResponse.java` — `subjectTimes` 컴포넌트를 교체하고 `from`에서 매핑한다:

```java
        @Schema(description = "과목 구간 — 시작 시각 오름차순. 자정 분할 조각에는 잘린 구간이 담기고 파생값은 그 조각 이벤트로 계산된다. 없으면 []")
        List<SubjectSegmentResponse> subjectSegments,
```

```java
                session.getSubjectSegments().stream()
                        .map(SubjectSegmentResponse::from)
                        .toList(),
```

`LegacyStudySessionCreateRequest`·`LegacyActiveSessionSnapshotRequest`는 코드 변경 없음(6번째 인자 `null`). `LegacyStudySessionCreateRequest.toRequest()`의 주석 "구 앱은 과목 시트가 없다 — 항목별 시간은 항상 비어 있다"를 "— 과목 구간은 항상 비어 있다"로 바꾼다.

- [ ] **Step 6: 검증·절단·계산**

`src/main/java/project/study/studysession/service/StudySessionValidator.java` — `validateSubjectTimes`를 지우고 아래로 교체. import `StudySessionSubjectTime` → `project.study.studysession.dto.SubjectSegmentRequest`.

```java
    /**
     * 과목 구간(ADR-0023) — 이벤트와 같은 규칙: 종료 > 시작, 세션 구간 안, 서로 겹치지 않음(맞닿음 허용). 정렬된 목록을 전제한다.
     * 구간 합을 세션 studySec에 묶지 않는다(ADR-0021 §4). 소유 검증은 세션 도메인 밖(StudySubjectService.assertOwned)에서 한다.
     */
    static void validateSubjectSegments(Instant startedAt, Instant endedAt, List<SubjectSegmentRequest> sortedSegments) {
        SubjectSegmentRequest previous = null;
        for (SubjectSegmentRequest segment : sortedSegments) {
            if (!segment.endedAt().isAfter(segment.startedAt())) {
                throw new InvalidSessionException("과목 구간 종료 시각은 시작 시각 이후여야 합니다");
            }
            if (segment.startedAt().isBefore(startedAt) || segment.endedAt().isAfter(endedAt)) {
                throw new InvalidSessionException("과목 구간은 세션 구간 안에 있어야 합니다");
            }
            if (previous != null && segment.startedAt().isBefore(previous.endedAt())) {
                throw new InvalidSessionException("과목 구간이 서로 겹칠 수 없습니다");
            }
            previous = segment;
        }
    }
```

생성 `src/main/java/project/study/studysession/service/SubjectSegmentSplitter.java`:

```java
package project.study.studysession.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySessionSubjectSegment;

/**
 * 과목 구간을 자정 조각으로 자르고, 그 조각의 비공부 이벤트로 과목별 총공부·순공을 계산하는 순수 로직 (ADR-0023).
 * 앱 타이머가 멈추는 규칙과 같다 — PAUSE는 총공부·순공 둘 다에서, 나머지 이벤트는 순공에서만 빠진다.
 * 이벤트끼리는 겹치지 않으므로(검증됨) 겹침 길이의 합이 곧 정확한 값이다.
 */
final class SubjectSegmentSplitter {

    private SubjectSegmentSplitter() {}

    /**
     * 정렬된 구간들을 [pieceStart, pieceEnd)로 잘라 조각 몫의 행을 만든다 — 0초 조각은 버린다.
     * 길이는 이벤트와 같이 초 단위 절삭이고, floor(a)+floor(b) ≤ floor(a+b)라 파생값이 음수가 되지 않는다(안전하게 0 하한).
     */
    static List<StudySessionSubjectSegment> clip(
            List<SubjectSegmentRequest> sorted, List<StatusEvent> pieceEvents, Instant pieceStart, Instant pieceEnd) {
        List<StudySessionSubjectSegment> clipped = new ArrayList<>();
        for (SubjectSegmentRequest segment : sorted) {
            Instant start = later(segment.startedAt(), pieceStart);
            Instant end = earlier(segment.endedAt(), pieceEnd);
            if (!start.isBefore(end)) {
                continue;
            }
            long length = Duration.between(start, end).toSeconds();
            long pauseOverlap = 0;
            long anyOverlap = 0;
            for (StatusEvent event : pieceEvents) {
                long overlap = overlapSec(start, end, event.getStartedAt(), event.getEndedAt());
                anyOverlap += overlap;
                if (event.getStatus() == EventStatus.PAUSE) {
                    pauseOverlap += overlap;
                }
            }
            clipped.add(new StudySessionSubjectSegment(
                    segment.subjectId(),
                    start,
                    end,
                    (int) Math.max(0, length - pauseOverlap),
                    (int) Math.max(0, length - anyOverlap)));
        }
        return clipped;
    }

    private static long overlapSec(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        Instant start = later(aStart, bStart);
        Instant end = earlier(aEnd, bEnd);
        return start.isBefore(end) ? Duration.between(start, end).toSeconds() : 0;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
```

`src/main/java/project/study/studysession/service/StudySessionSplitter.java`:
- import `StudySessionSubjectTime` → `project.study.studysession.dto.SubjectSegmentRequest`.
- `buildSessions`의 6번째 파라미터를 `List<SubjectSegmentRequest> subjectSegments`로 바꾸고, 본문에서 `subjectTimesBySegment` 계산 줄을 지운 뒤 루프 안 `session.attachSubjectTimes(...)`를 아래로 교체:

```java
            session.attachSubjectSegments(SubjectSegmentSplitter.clip(
                    subjectSegments, weights.segmentEvents().get(i), cuts.get(i), cuts.get(i + 1)));
```

- Javadoc: "studySec/focusSec(과목·할 일별 시간 포함)을 비례 배분해" → "studySec/focusSec을 비례 배분해 세션들을 만든다 — 과목 구간은 배분하지 않고 조각에 맞춰 잘라 다시 계산한다(ADR-0023)".
- `splitSubjectTimes` 메서드 전체 삭제.

`src/main/java/project/study/studysession/service/SessionAttachments.java`:

```java
package project.study.studysession.service;

import java.util.List;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.dto.SubjectSegmentRequest;

/**
 * 세션에 함께 붙는 부가 기록 — 과목 구간은 조각에 맞춰 잘라 다시 계산되고(ADR-0023), 완료 할 일은 완료 시각이 속한 조각에
 * 붙는다(ADR-0022). 검증·분할 진입점의 파라미터 수를 묶어 두려고 한 값으로 넘긴다.
 */
record SessionAttachments(List<SubjectSegmentRequest> subjectSegments, List<CompletedTask> completedTasks) {
    static final SessionAttachments NONE = new SessionAttachments(List.of(), List.of());
}
```

`src/main/java/project/study/studysession/service/StudySessionService.java`:
- import `StudySessionSubjectTime` → `SubjectSegmentRequest`; static import `validateSubjectTimes` → `validateSubjectSegments`.
- `create(...)`의 attachments: `new SessionAttachments(request.subjectSegmentsOrEmpty(), completedTasks)`.
- 7인자 오버로드 시그니처·Javadoc:

```java
    /** 과목 구간(subjectSegments)도 함께 검증하고 조각마다 잘라 그 조각의 이벤트로 과목별 시간을 계산한다 (ADR-0023). */
    List<StudySession> validateAndBuildSessions(
            Long userId,
            Instant startedAt,
            Instant endedAt,
            int studySec,
            int focusSec,
            List<StatusEvent> events,
            List<SubjectSegmentRequest> subjectSegments) {
        return validateAndBuildSessions(
                userId,
                startedAt,
                endedAt,
                studySec,
                focusSec,
                events,
                new SessionAttachments(subjectSegments, List.of()));
    }
```

- attachments 오버로드 본문에서 `validateSubjectTimes(attachments.subjectTimes(), studySec);`를 아래로 교체하고, `buildSessions` 호출의 6번째 인자를 `sortedSegments`로 바꾼다:

```java
        List<SubjectSegmentRequest> sortedSegments = attachments.subjectSegments().stream()
                .sorted(Comparator.comparing(SubjectSegmentRequest::startedAt))
                .toList();
        validateSubjectSegments(startedAt, endedAt, sortedSegments);

        return buildSessions(userId, cuts, weights, studySec, focusSec, sortedSegments, attachments.completedTasks());
```

- [ ] **Step 7: 스냅샷·복구 경로**

`src/main/java/project/study/studysession/entity/ActiveStudySession.java` — 마지막 필드 교체:

```java
    // 과목 구간 스냅샷(SubjectSegmentRequest 배열의 JSON) — 확정 시 study_session_subject_segment 행이 된다 (ADR-0023)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subject_segments", nullable = false)
    private String subjectSegments;
```

`src/main/java/project/study/studysession/repository/ActiveStudySessionBatchRepository.java`:
- SQL의 컬럼 목록 `subject_times` → `subject_segments`(INSERT 컬럼·`SET subject_times = excluded.subject_times` 둘 다).
- `ps.setString(8, r.subjectSegmentsJson());`
- `SnapshotRow`의 마지막 컴포넌트 `String subjectSegmentsJson`, Javadoc "events·subjectSegments는 이미 JSON 문자열로 직렬화된 상태다."

`src/main/java/project/study/studysession/buffer/ActiveSnapshotBuffer.java` — `flush()`:

```java
            String subjectSegmentsJson = objectMapper.writeValueAsString(r.subjectSegmentsOrEmpty());
            rows.add(new SnapshotRow(
                    p.userId(),
                    r.startedAt(),
                    r.reportedAt(),
                    p.lastSeenAt(),
                    r.studySec(),
                    r.focusSec(),
                    eventsJson,
                    subjectSegmentsJson));
```

`src/main/java/project/study/studysession/service/ActiveStudySessionService.java`:
- import `SubjectTimeRequest` → `SubjectSegmentRequest`.
- `reportSnapshot`: `request.getSubjectTimeList()` → `request.subjectSegmentsOrEmpty()`.
- `findLatestSnapshot`: `parseSubjectTimes(draft)` → `parseSubjectSegments(draft)`.
- `finalizeDraft`: `parseSubjectTimes(draft)` → `parseSubjectSegments(draft)`.
- 메서드 교체:

```java
    /** V20 이전 draft는 없다(배포 전 교체) — 컬럼 기본값 '[]'라 항상 파싱된다. */
    private List<SubjectSegmentRequest> parseSubjectSegments(ActiveStudySession draft) {
        return objectMapper.readValue(draft.getSubjectSegments(), new TypeReference<List<SubjectSegmentRequest>>() {});
    }
```

- [ ] **Step 8: 과목 도메인 — 소유 검증 시그니처·누적 합산**

`src/main/java/project/study/subject/service/StudySubjectService.java`:
- import `SubjectTimeRequest` 삭제, `StudySessionSubjectTimeRepository` → `StudySessionSubjectSegmentRepository`, `java.util.Collection` 추가.
- 필드 `private final StudySessionSubjectSegmentRepository subjectSegmentRepository;` (기존 `subjectTimeRepository` 대체) — `toResponses`의 `subjectTimeRepository.sumBySubjectIds(subjectIds)` → `subjectSegmentRepository.sumBySubjectIds(subjectIds)`.
- 클래스 Javadoc "세션이 보내는 항목별 시간의 소유 검증" → "세션이 보내는 과목 구간의 소유 검증".
- `assertOwned` 교체:

```java
    /**
     * 세션 제출·스냅샷의 과목 구간이 토큰 유저의 과목인지 확인한다 — 위반은 400. 세션 중 지운 과목은 허용한다:
     * 거절하면 공부 기록 전체가 함께 거절되고, 정책상 삭제해도 시간 기록은 남기기 때문이다 (ADR-0021 §1, ADR-0023).
     */
    @Transactional(readOnly = true)
    public void assertOwned(Long userId, Collection<Long> subjectIds) {
        if (subjectIds.isEmpty()) {
            return;
        }
        Set<Long> owned = subjectRepository.findByIdInAndUserId(subjectIds, userId).stream()
                .map(StudySubject::getId)
                .collect(toSet());
        if (!owned.containsAll(subjectIds)) {
            throw new BadRequestException("사용자의 과목이 아닙니다");
        }
    }
```

`src/main/java/project/study/subject/controller/StudySubjectController.java` 문구 두 곳:
- 태그: "과목별 시간은 세션 제출·스냅샷의 `subjectTimes`로 들어오고 여기서는 누적 합계만 내려준다." → "과목별 시간은 세션 제출·스냅샷의 과목 구간(`subjectSegments`)으로 들어오고 서버가 계산한 값의 누적 합계만 여기서 내려준다."
- 목록: "**누적 시간** = 저장된 모든 세션의 `subjectTimes` 합." → "**누적 시간** = 저장된 모든 세션의 과목 구간(`subjectSegments`)에서 서버가 계산한 총공부·순공의 합."

`src/test/java/project/study/subject/service/StudySubjectServiceTest.java` — import `SubjectTimeRequest` 삭제, `java.util.Set` 추가(없으면), 167행:

```java
        assertThatThrownBy(() -> service.assertOwned(1L, Set.of(5L)))
```

또 이 테스트가 `StudySessionSubjectTimeRepository`를 mock으로 주입하면 `StudySessionSubjectSegmentRepository`로 바꾼다(`grep -n 'SubjectTimeRepository' src/test/java -r`로 확인).

- [ ] **Step 9: 컨트롤러 — 소유 검증 호출·Swagger**

`src/main/java/project/study/studysession/controller/StudySessionController.java`:
- `subjectService.assertOwned(userId, request.subjectTimesOrEmpty());` → `subjectService.assertOwned(userId, request.subjectIds());`
- 201 description: `studySec/focusSec/focusRate/statDate·subjectTimes·completedTaskIds` → `studySec/focusSec/focusRate/statDate·subjectSegments·completedTaskIds`.
- `@Operation` description의 "**완료한 할 일**" 문단 바로 앞에 문단 추가:

```
                    **과목 구간** — `subjectSegments`로 과목을 선택한 채 공부한 구간(subjectId/startedAt/endedAt)을 함께 보낸다(선택, ADR-0023). \
                    검증은 이벤트와 같다(세션 안·겹침 없음·맞닿음 허용·순서 무관). 과목별 총공부·순공은 앱이 보내지 않고 서버가 구간과 \
                    비공부 이벤트를 겹쳐 계산해 응답 `subjectSegments`에 싣는다 — PAUSE 겹침은 둘 다에서, 나머지는 순공에서만 뺀다. \
                    자정 분할이면 구간도 자정에서 잘려 조각마다 다시 계산된다.

```
- 400 examples에 추가: `@ExampleObject(name = "과목 구간 겹침", value = "{\"message\": \"과목 구간이 서로 겹칠 수 없습니다\"}")`, `@ExampleObject(name = "남의 과목", value = "{\"message\": \"사용자의 과목이 아닙니다\"}")`.

`src/main/java/project/study/studysession/controller/ActiveStudySessionController.java`:
- `subjectService.assertOwned(userId, request.subjectTimesOrEmpty());` → `subjectService.assertOwned(userId, request.subjectIds());`
- 복구 200 description: `"진행중 스냅샷 — startedAt/reportedAt/studySec/focusSec/events"` → `"진행중 스냅샷 — startedAt/reportedAt/studySec/focusSec/events/subjectSegments"`.

`src/test/java/project/study/studysession/StudySessionControllerRetryTest.java` 41행 주석: `subjectTimes는 비어 있어` → `subjectSegments는 비어 있어`.

- [ ] **Step 10: 컴파일·단위테스트**

Run: `./gradlew spotlessApply -q && ./gradlew test --tests "project.study.studysession.service.SubjectSegmentSplitTest" -q 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, 11 tests pass. 실패하면 메시지·계산을 고친다(구현 쪽을 고친다 — 테스트 기대값은 스펙 §4.2 그대로다).

- [ ] **Step 11: API 테스트 작성 — `StudySessionSubjectSegmentApiTest`**

`src/test/java/project/study/studysession/StudySessionSubjectTimeApiTest.java`를 삭제하고 아래를 만든다.

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import project.study.config.ApiVersionConfig;
import project.study.studysession.buffer.ActiveSnapshotBuffer;
import project.study.studysession.service.ActiveStudySessionService;

/** BY-733 과목 구간 계약 — 제출·스냅샷·복구·자동 확정의 subjectSegments 저장·검증·자정 절단·파생 계산·누적 합산 (ADR-0023). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionSubjectSegmentApiTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    @Autowired
    private ActiveStudySessionService activeStudySessionService;

    private Long userId;
    private Long subjectId;

    private final LocalDate today = LocalDate.now(KST);
    // 어제 12:00~14:00 (KST) — 항상 과거라 미래 검증에 걸리지 않는다
    private final Instant sessionStart =
            today.minusDays(1).atStartOfDay(KST).plusHours(12).toInstant();
    private final Instant sessionEnd = sessionStart.plusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = insertUser();
        subjectId = insertSubject(userId, "수학");
    }

    // 스케줄러 풀스캔 오염 방지 — ActiveSessionSnapshotApiTest와 같은 이유
    @AfterEach
    void cleanUpDraft() {
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private Long insertSubject(Long ownerId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO study_subject (user_id, name) VALUES (?, ?) RETURNING id", Long.class, ownerId, name);
    }

    private static String segment(Long subject, Instant start, Instant end) {
        return """
                {"subjectId": %d, "startedAt": "%s", "endedAt": "%s"}""".formatted(subject, start, end);
    }

    private static String event(String status, Instant start, Instant end) {
        return """
                {"status": "%s", "startedAt": "%s", "endedAt": "%s"}""".formatted(status, start, end);
    }

    private MvcTestResult submit(
            Instant start, Instant end, int studySec, int focusSec, String eventsJson, String segmentsJson) {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": %d, "focusSec": %d, "events": %s, "subjectSegments": %s}""".formatted(start, end, studySec, focusSec, eventsJson, segmentsJson);
        return mvc.post()
                .uri("/api/study-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(userId))
                .exchange();
    }

    @Test
    void 세션_제출의_과목_구간이_저장되고_서버가_계산한_값이_과목_누적에_합산된다() {
        // 12:00~13:00 수학, 13:00~13:30 수학. PHONE 12:10~12:20(10분)은 첫 구간 순공에서만 빠진다
        String events = "[" + event("PHONE", sessionStart.plusSeconds(600), sessionStart.plusSeconds(1200)) + "]";
        String segments = "[" + segment(subjectId, sessionStart.plusSeconds(3600), sessionStart.plusSeconds(5400)) + ","
                + segment(subjectId, sessionStart, sessionStart.plusSeconds(3600)) + "]";

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, events, segments))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].subjectSegments.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$[0].subjectSegments[0].startedAt", v -> assertThat(v).isEqualTo(sessionStart.toString()))
                .hasPathSatisfying("$[0].subjectSegments[0].studySec", v -> assertThat(v).isEqualTo(3600))
                .hasPathSatisfying("$[0].subjectSegments[0].focusSec", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$[0].subjectSegments[1].studySec", v -> assertThat(v).isEqualTo(1800))
                .hasPathSatisfying("$[0].subjectSegments[1].focusSec", v -> assertThat(v).isEqualTo(1800));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session_subject_segment WHERE subject_id = ?", Integer.class, subjectId);
        assertThat(rows).isEqualTo(2);

        assertThat(mvc.get()
                        .uri("/api/subjects")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].studySec", v -> assertThat(v).isEqualTo(5400))
                .hasPathSatisfying("$[0].focusSec", v -> assertThat(v).isEqualTo(4800));
    }

    @Test
    void 과목_구간_없이_제출하면_기존처럼_저장된다() {
        String body = """
                {"startedAt": "%s", "endedAt": "%s", "studySec": 7200, "focusSec": 6600, "events": []}""".formatted(sessionStart, sessionEnd);

        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$[0].subjectSegments.length()", v -> assertThat(v).isEqualTo(0));
    }

    @Test
    void 과목_구간이_겹치면_400이고_세션도_저장되지_않는다() {
        String segments = "[" + segment(subjectId, sessionStart, sessionStart.plusSeconds(3600)) + ","
                + segment(subjectId, sessionStart.plusSeconds(3000), sessionStart.plusSeconds(5400)) + "]";

        assertThat(submit(sessionStart, sessionEnd, 7200, 6600, "[]", segments)).hasStatus(HttpStatus.BAD_REQUEST);

        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(sessions).isZero();
    }

    @Test
    void 다른_사용자의_과목이면_400이다() {
        Long otherSubjectId = insertSubject(insertUser(), "남의 과목");

        assertThat(submit(
                        sessionStart,
                        sessionEnd,
                        7200,
                        6600,
                        "[]",
                        "[" + segment(otherSubjectId, sessionStart, sessionStart.plusSeconds(600)) + "]"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 자정을_넘는_제출은_과목_구간도_자정에서_잘려_두_조각에_담긴다() {
        // 그저께 23:00 ~ 어제 01:00 (KST), 구간 23:30~00:30
        Instant start = today.minusDays(2).atStartOfDay(KST).plusHours(23).toInstant();
        Instant midnight = today.minusDays(1).atStartOfDay(KST).toInstant();
        Instant end = start.plusSeconds(7200);

        assertThat(submit(
                        start,
                        end,
                        7200,
                        6000,
                        "[]",
                        "[" + segment(subjectId, start.plusSeconds(1800), midnight.plusSeconds(1800)) + "]"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$[0].subjectSegments[0].endedAt", v -> assertThat(v).isEqualTo(midnight.toString()))
                .hasPathSatisfying("$[0].subjectSegments[0].studySec", v -> assertThat(v).isEqualTo(1800))
                .hasPathSatisfying(
                        "$[1].subjectSegments[0].startedAt", v -> assertThat(v).isEqualTo(midnight.toString()))
                .hasPathSatisfying("$[1].subjectSegments[0].focusSec", v -> assertThat(v).isEqualTo(1800));
    }

    @Test
    void 스냅샷의_과목_구간은_복구_조회에_그대로_돌아온다() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant reported = Instant.now().minusSeconds(60);
        Instant segmentEnd = started.plusSeconds(1800);
        String body = """
                {"startedAt": "%s", "reportedAt": "%s", "studySec": 7000, "focusSec": 6500, "events": [], "subjectSegments": [%s]}""".formatted(started, reported, segment(subjectId, started, segmentEnd));

        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);
        buffer.flush();

        assertThat(mvc.get().uri("/api/study-sessions/active").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.subjectSegments.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying(
                        "$.subjectSegments[0].subjectId", v -> assertThat(v).isEqualTo(subjectId.intValue()))
                .hasPathSatisfying("$.subjectSegments[0].endedAt", v -> assertThat(v).isEqualTo(segmentEnd.toString()));
    }

    @Test
    void 스냅샷이_reportedAt_밖의_구간을_담으면_400이다() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant reported = Instant.now().minusSeconds(60);
        String body = """
                {"startedAt": "%s", "reportedAt": "%s", "studySec": 7000, "focusSec": 6500, "events": [], "subjectSegments": [%s]}""".formatted(started, reported, segment(subjectId, started, reported.plusSeconds(30)));

        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 자동_확정본에도_과목_구간이_그대로_확정된다() {
        Instant started = Instant.now().minusSeconds(7200);
        Instant reported = Instant.now().minusSeconds(60);
        String body = """
                {"startedAt": "%s", "reportedAt": "%s", "studySec": 7000, "focusSec": 6500, "events": [], "subjectSegments": [%s]}""".formatted(started, reported, segment(subjectId, started, started.plusSeconds(1800)));
        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(asUser(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);
        buffer.flush();
        Long draftId = jdbcTemplate.queryForObject(
                "SELECT id FROM active_study_session WHERE user_id = ?", Long.class, userId);

        activeStudySessionService.finalizeDraft(draftId);

        Integer rows = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM study_session_subject_segment seg
                JOIN study_session s ON s.id = seg.session_id
                WHERE s.user_id = ? AND s.auto_finalized""",
                Integer.class,
                userId);
        assertThat(rows).isEqualTo(1);
    }
}
```

- [ ] **Step 12: 전체 검증**

Run: `./gradlew spotlessApply -q && ./gradlew check 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. 흔한 실패와 대응:
- Flyway "more than one migration with version 20" → `git fetch origin && git log --oneline origin/dev -3`로 dev에 V20이 먼저 들어갔는지 확인, 들어갔으면 V21로 이름을 바꾼다.
- Hibernate validate "missing column subject_segments" → V20 컬럼명과 엔티티 `@Column(name)` 대조.
- Checkstyle `FileLength`(400) — `StudySessionService`가 넘으면 `validateAndBuildSessions`의 6·7인자 오버로드 중 6인자만 남기고 테스트가 7인자를 쓰도록 유지(둘 다 필요). 넘을 때만 `SessionAttachments` 생성 헬퍼를 `SessionAttachments` 레코드로 옮긴다.
- 테스트가 멈추면 Docker Desktop 재시작(메모: dev-server-cd).

- [ ] **Step 13: Codex 2차 리뷰**

Run:
```bash
codex exec -s read-only "backend 리포의 현재 uncommitted diff(git diff)를 리뷰해줘. 맥락: 세션 제출·스냅샷·복구의 과목별 합계(subjectTimes)를 과목 구간(subjectSegments)으로 교체하고 서버가 구간과 비공부 이벤트로 과목별 studySec(길이−PAUSE 겹침)·focusSec(길이−모든 이벤트 겹침)을 계산한다. 자정 분할은 구간을 자정에서 자르고 조각별 이벤트로 재계산. 검증은 이벤트와 같은 규칙(종료>시작, 세션 안, 겹침 없음). 스키마 V20이 옛 테이블을 DROP한다(배포 전 계약). 버그·경계값(절삭·자정·0초)·N+1·동시성·계약 누락을 P1/P2/P3로 나눠 지적해줘. 설계 문서: docs/superpowers/specs/2026-09-22-by556-record-tab-v2-design.md §4"
```
P1이 나오면 고치고 Step 12를 다시 돈다. P2는 타당하면 고치고 아니면 근거를 PR 본문에 남긴다.

- [ ] **Step 14: 퀴즈 게이트**

사용자에게 구현 코드·흐름 퀴즈 5개를 낸다(예: "PAUSE 겹침이 focusSec에서도 빠지는 이유", "자정에 정확히 끝나는 구간이 둘째 조각에 행을 만들지 않는 코드 위치", "assertOwned가 지운 과목을 허용하는 이유", "스냅샷 구간 검증에서 세션 끝으로 쓰는 값", "과목 누적 합산 쿼리가 어느 테이블을 보는지"). 통과할 때까지 다른 퀴즈를 낸다. 통과 전엔 커밋하지 않는다.

- [ ] **Step 15: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: 세션 과목 시간을 구간 계약으로 교체하고 서버가 계산한다 (BY-733)

제출·스냅샷·복구의 subjectTimes(과목별 합계)를 subjectSegments(과목 전환
구간)로 바꾼다. 비공부 이벤트가 시각만 보내고 길이는 서버가 계산하는 것과
같은 원칙이라, 과목별 총공부(길이−PAUSE 겹침)·순공(길이−모든 이벤트 겹침)은
서버가 구간과 이벤트를 겹쳐 계산해 행에 같이 둔다. 자정 분할은 구간을 잘라
조각별 이벤트로 다시 계산하고 비례 배분은 사라진다. 과목 시트 앱이 배포
전이라 V20이 합계 테이블과 스냅샷 컬럼을 이관 없이 교체한다 (ADR-0023).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: dev 시더가 과목과 구간을 심는다

**Files:**
- Modify: `src/main/java/project/study/config/DevDataSeeder.java`

**Interfaces:**
- Consumes: `StudySubjectService.list(Long)`, `StudySubjectService.create(Long, String)` → `SubjectResponse(id, name, colorIndex, studySec, focusSec, tasks)`; `StudySessionCreateRequest` 6번째 인자 `List<SubjectSegmentRequest>`.

- [ ] **Step 1: 시더 수정**

- import 추가: `project.study.studysession.dto.SubjectSegmentRequest`, `project.study.subject.dto.SubjectResponse`, `project.study.subject.service.StudySubjectService`.
- 필드 추가: `private final StudySubjectService subjectService;`
- `run()`에서 `studySessionRepository.deleteByUserId(userId);` 다음 줄에 `List<Long> subjectIds = seedSubjects(userId);`를 넣고 `seedCuratedSessions(userId, now, today, subjectIds)`로 넘긴다. 로그 문구 끝에 `", 과목 {}개"`와 `subjectIds.size()`를 추가한다.
- 메서드 추가:

```java
    /** 데모 과목 3개 — 이미 있으면(재시작) 살아있는 과목을 순서대로 다시 쓴다. 과목은 soft delete라 세션처럼 갈아끼우지 않는다. */
    private List<Long> seedSubjects(Long userId) {
        List<SubjectResponse> existing = subjectService.list(userId);
        if (existing.size() >= 3) {
            return existing.subList(0, 3).stream().map(SubjectResponse::id).toList();
        }
        List<Long> ids = new ArrayList<>(existing.stream().map(SubjectResponse::id).toList());
        for (String name : List.of("영어", "수학", "국어").subList(existing.size(), 3)) {
            ids.add(subjectService.create(userId, name).id());
        }
        return ids;
    }
```

- `seedCuratedSessions(Long userId, Instant now, LocalDate today, List<Long> subjects)`로 시그니처를 바꾸고, 세션 1·2·3 제출에 구간을 싣는다. 세션 4·5와 랜덤 세션은 구간 없이 그대로(`submit(...)` 4인자 오버로드 유지).

```java
        // 1) 오늘: 3시간 전~1시간 전 2시간 세션 — 영어 0~50분, 수학 50~100분 (100~120분은 과목 미선택)
        Instant s1 = now.minus(Duration.ofHours(3));
        submit(
                userId,
                s1,
                now.minus(Duration.ofHours(1)),
                List.of(
                        event(EventStatus.PHONE, s1.plus(Duration.ofMinutes(30)), s1.plus(Duration.ofMinutes(40))),
                        event(EventStatus.AWAY, s1.plus(Duration.ofMinutes(70)), s1.plus(Duration.ofMinutes(80))),
                        event(EventStatus.SLEEP, s1.plus(Duration.ofMinutes(100)), s1.plus(Duration.ofMinutes(110)))),
                List.of(
                        segment(subjects.get(0), s1, s1.plus(Duration.ofMinutes(50))),
                        segment(subjects.get(1), s1.plus(Duration.ofMinutes(50)), s1.plus(Duration.ofMinutes(100)))));

        // 2) 어제 14~17시: DEVICE 20분 + PAUSE 10분 — 국어 3시간 내내
        Instant s2 = kst(today.minusDays(1), 14);
        submit(
                userId,
                s2,
                kst(today.minusDays(1), 17),
                List.of(
                        event(EventStatus.DEVICE, s2.plus(Duration.ofMinutes(60)), s2.plus(Duration.ofMinutes(80))),
                        event(EventStatus.PAUSE, s2.plus(Duration.ofMinutes(120)), s2.plus(Duration.ofMinutes(130)))),
                List.of(segment(subjects.get(2), s2, kst(today.minusDays(1), 17))));

        // 3) 2일 전 20:00~21:30: 이벤트 없음 (집중률 100%) — 수학 45분, 영어 45분
        Instant s3 = kst(today.minusDays(2), 20);
        submit(
                userId,
                s3,
                s3.plus(Duration.ofMinutes(90)),
                List.of(),
                List.of(
                        segment(subjects.get(1), s3, s3.plus(Duration.ofMinutes(45))),
                        segment(subjects.get(0), s3.plus(Duration.ofMinutes(45)), s3.plus(Duration.ofMinutes(90)))));
```

- `submit` 오버로드·헬퍼:

```java
    private void submit(Long userId, Instant startedAt, Instant endedAt, List<StatusEventRequest> events) {
        submit(userId, startedAt, endedAt, events, null);
    }

    private void submit(
            Long userId,
            Instant startedAt,
            Instant endedAt,
            List<StatusEventRequest> events,
            List<SubjectSegmentRequest> segments) {
        // studySec/focusSec는 요청값이 그대로 저장되므로, 앱이 보내듯 값을 계산해 보낸다.
        // studySec은 PAUSE(일시정지) 구간만 빼고, focusSec은 전체 이벤트 구간을 뺀다. 과목별 값은 서버가 구간으로 계산한다.
        long totalSec = Duration.between(startedAt, endedAt).toSeconds();
        long pauseSec = events.stream()
                .filter(event -> event.status() == EventStatus.PAUSE)
                .mapToLong(event ->
                        Duration.between(event.startedAt(), event.endedAt()).toSeconds())
                .sum();
        long nonFocusSec = events.stream()
                .mapToLong(event ->
                        Duration.between(event.startedAt(), event.endedAt()).toSeconds())
                .sum();
        int studySec = (int) (totalSec - pauseSec);
        int focusSec = (int) (totalSec - nonFocusSec);
        studySessionService.create(
                userId,
                new StudySessionCreateRequest(startedAt, endedAt, studySec, focusSec, events, segments, null),
                false);
    }

    private static SubjectSegmentRequest segment(Long subjectId, Instant startedAt, Instant endedAt) {
        return new SubjectSegmentRequest(subjectId, startedAt, endedAt);
    }
```

- 클래스 Javadoc 첫 줄: "데모 유저와 엣지케이스 세션 5건" → "데모 유저·과목 3개와 엣지케이스 세션 5건(앞 3건에 과목 구간)".

- [ ] **Step 2: 시더 실행 확인**

Run: `docker compose up -d && ./gradlew bootRun --args='--spring.profiles.active=local' > /tmp/claude-bootrun.log 2>&1 &` 뒤 `sleep 40 && grep -E 'dev 목데이터 시딩 완료|ERROR|Exception' /tmp/claude-bootrun.log | head`, 그리고 `curl -s localhost:8080/actuator/health`.
Expected: "dev 목데이터 시딩 완료 … 과목 3개" 로그, health UP. 로컬 yaml에 `app.seed.enabled: true`가 없으면(`grep seed src/main/resources/application-local.yaml`) 임시로 `--app.seed.enabled=true`를 args에 붙인다. 확인 뒤 `pkill -f 'bootRun'`.

- [ ] **Step 3: 검증·커밋**

Run: `./gradlew spotlessApply -q && ./gradlew check 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/java/project/study/config/DevDataSeeder.java
git commit -m "$(cat <<'EOF'
feat: dev 시더가 데모 과목과 세션 과목 구간을 심는다 (BY-733)

dev 서버 기록 탭 프로토타입에서 과목 색 타임테이블이 보이도록 데모 유저에
과목 3개를 만들고 큐레이션 세션 3건에 구간을 싣는다. 과목은 soft delete라
재시작마다 갈아끼우지 않고 살아있는 것을 재사용한다.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 문서 — ADR-0023·ADR-0021 갱신 표시·ERD

**Files:**
- Create: `docs/adr/0023-subject-segments-server-derived.md`
- Modify: `docs/adr/0021-subject-task-session-time.md`, `docs/erd.dbml`

- [ ] **Step 1: ADR-0023 작성**

```markdown
# ADR-0023: 과목 시간을 구간으로 받고 서버가 과목별 시간을 계산한다

- 상태: 승인
- 날짜: 2026-09-22
- 티켓: BY-733 (스토리 BY-556, 후행 [BE] BY-734 · [FE] BY-735)
- 선행: ADR-0021 (과목 > 할 일과 세션 과목 시간) — §3·§4를 이 ADR이 갱신한다
- 설계: `docs/superpowers/specs/2026-09-22-by556-record-tab-v2-design.md`

## 맥락

기록 탭 v2의 24시간 타임테이블은 2분 칸마다 "그때 공부한 과목 색"을 칠한다. ADR-0021은 세션당 과목별
**합계**(`study_session_subject_time`)만 저장해 "언제 어느 과목을 했는지"를 모른다. 프로토타입은 합계 비율로
세션을 앞뒤로 쪼개 칠한 목업이었고, 그대로 가면 화면이 실제와 다른 순서를 보여준다.

과목 시트(BY-696)가 들어간 앱은 배포 전이라 `subjectTimes` 계약을 바꿀 비용이 지금이 가장 싸다.

## 결정

1. **앱은 과목 전환 구간만 보낸다.** 세션 제출·스냅샷·복구의 `subjectTimes[]`(과목별 순공·총공부 합계)를 없애고
   `subjectSegments[]`(subjectId, startedAt, endedAt)로 **교체**한다. 비공부 이벤트가 시각만 보내고 길이는 서버가
   계산하는 것과 같은 원칙이다. 같은 사실을 합계와 구간 두 형태로 받는 안은 기각 — 앱 버그로 둘이 어긋나면 화면이
   자기모순을 보여준다.
2. **과목별 총공부·순공은 서버가 계산한다.** 구간마다 `studySec = 길이 − PAUSE 겹침`, `focusSec = 길이 − 모든 이벤트
   겹침`. 앱 타이머가 멈추는 규칙과 같은 계산이라 앱이 보내던 값과 같은 결과가 나온다. 이벤트끼리는 겹치지 않으므로
   겹침 합이 정확하다. 길이는 이벤트와 같이 초 절삭이며 `floor(a)+floor(b) ≤ floor(a+b)`라 음수가 나오지 않는다.
3. **세션 단위 값은 여전히 앱을 믿는다.** `studySec`·`focusSec`는 ADR-0006·0008대로 제출값 그대로이고, 구간 합을 세션
   값에 맞추는 검증은 두지 않는다(ADR-0021 §4의 태도 유지). 구간 계산은 세션 값의 *배분*이 아니라 독립된 파생이다.
4. **검증은 이벤트와 같다.** 종료 > 시작, 세션(스냅샷은 reportedAt) 구간 안, 서로 겹치지 않음(맞닿음 허용), 순서 무관,
   `subjectId`는 토큰 유저의 과목(지운 과목 허용, ADR-0021 §1). 위반 400. 개수 상한은 이벤트처럼 두지 않는다.
   소유 검증은 ADR-0021 §6대로 컨트롤러가 `StudySubjectService.assertOwned`를 먼저 부른다.
5. **자정 분할은 구간을 자정에서 자르고 조각마다 그 조각의 이벤트로 다시 계산한다.** 시간처럼 비례 배분하지 않는다 —
   구간은 시각을 가진 사실이라 자를 수 있다. 0초 조각은 행을 만들지 않는다(정확히 자정에 끝나는 구간은 첫 조각에만).
6. **저장은 구간 행에 파생값을 함께 둔 테이블 하나다.** `study_session_subject_segment(session_id, subject_id,
   started_at, ended_at, study_sec, focus_sec)`가 `study_session_subject_time`을 대체한다(V20). 과목 누적
   (`GET /api/subjects`)이 지금처럼 `subject_id` 합산 한 문장이어야 하기 때문이다(ADR-0021 §3의 근거 유지). 같은
   트랜잭션에서 같은 입력으로 쓰고 이후 바뀌지 않으므로 ADR-0007이 걱정한 파생값 동기화 버그 부류가 아니다.
   - 읽을 때 구간∩이벤트로 계산하는 안은 기각: 과목 누적이 이벤트까지 조인하는 집계가 된다.
7. **스냅샷·복구도 같은 필드다.** `active_study_session.subject_segments`(jsonb)에 통째로 덮어쓰고, 복구 응답이 요청과
   같은 모양을 돌려줘 앱이 마지막 구간의 과목으로 선택 상태를 복원한다. 자동 확정은 `create` 재사용이라 구간도 그대로
   확정된다. 진행 중인 구간은 앱이 reportedAt에서 닫아 보낸다.
8. **병행 없이 교체한다.** 배포된 앱은 `subjectTimes`를 보내지 않고 운영에 합계 행이 없다. 구 앱(API-Version 1)은 이
   필드를 애초에 모른다. V20은 합계 테이블과 스냅샷 컬럼을 이관 없이 버린다.

## 결과

- 계약: `subjectTimes[]` → `subjectSegments[]`(제출·스냅샷·복구), `StudySessionResponse.subjectSegments[]`(subjectId,
  startedAt, endedAt, studySec, focusSec, 시작 오름차순). 구간 사이 빈 시간은 "과목 미선택"이고 표현은 앱이 정한다.
- 스키마 V20: `study_session_subject_segment` 생성, `study_session_subject_time` 삭제, `active_study_session.subject_times`
  → `subject_segments`.
- 코드: `SubjectSegmentSplitter`(절단·계산 순수 로직), `StudySessionValidator.validateSubjectSegments`,
  `StudySubjectService.assertOwned(userId, subjectIds)`. 비례 배분(`splitSubjectTimes`)·합계 검증은 사라진다.
- dev 시더가 데모 과목 3개와 큐레이션 세션 3건의 구간을 심는다.
- 후속: BY-734가 일간 목록·상세 응답에 이벤트·구간·완료 할 일과 과목 이름·색을 싣는다(ADR-0022 §9의 읽기 후속).
```

- [ ] **Step 2: ADR-0021 갱신 표시**

`docs/adr/0021-subject-task-session-time.md` 맨 끝에 추가:

```markdown

## 갱신 (2026-09-22) — 과목 시간을 구간으로 받는다 (ADR-0023)

§3의 합계 테이블 `study_session_subject_time`과 비례 배분, §4의 합계 검증은 ADR-0023으로 대체됐다. 앱은 과목 전환
구간(`subjectSegments`)만 보내고 서버가 구간과 이벤트로 과목별 총공부·순공을 계산해 `study_session_subject_segment`에
둔다(V20). §1(soft delete·지운 과목 허용)·§5(스냅샷 동일 필드)·§6(소유 검증은 컨트롤러)·§7(구 앱 경로 없음)은 그대로다.
```

- [ ] **Step 3: ERD 갱신**

`docs/erd.dbml`의 `Table study_session_subject_time { ... }` 블록을 아래로 교체하고, 섹션 주석 "과목 > 할 일 / 세션 과목 시간 (ADR-0021, BY-698)"을 "과목 > 할 일 / 세션 과목 구간 (ADR-0021·0023)"로 바꾼다:

```dbml
Table study_session_subject_segment {
  id bigint [primary key, increment]
  session_id bigint [ref: > study_session.id, not null]   // ON DELETE CASCADE — 세션과 함께 사라진다
  subject_id bigint [ref: > study_subject.id, not null]
  started_at timestamptz [not null]   // 과목을 선택한 채 공부한 구간 — 자정 분할 조각에는 잘린 구간 (ADR-0023)
  ended_at timestamptz [not null]
  study_sec integer [not null]   // 서버 파생값: 길이 − PAUSE 겹침
  focus_sec integer [not null]   // 서버 파생값: 길이 − 모든 이벤트 겹침

  indexes {
    (session_id)
    (subject_id)   // 과목 누적 = 합
  }
}
```

`grep -n active_study_session docs/erd.dbml`로 스냅샷 테이블 블록이 있으면 `subject_times` 줄을 `subject_segments jsonb [not null, default: '[]'] // SubjectSegmentRequest 배열`로 바꾼다(없으면 건너뛴다).

- [ ] **Step 4: 검증·커밋**

Run: `./gradlew check 2>&1 | tail -3` (문서만 바뀌었지만 커밋 규칙대로)
Expected: BUILD SUCCESSFUL.

```bash
git add docs/adr/0023-subject-segments-server-derived.md docs/adr/0021-subject-task-session-time.md docs/erd.dbml
git commit -m "$(cat <<'EOF'
docs: 과목 시간을 구간으로 받는 결정을 ADR-0023으로 남긴다 (BY-733)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: PR·머지·티켓 완료

- [ ] **Step 1: push·PR**

```bash
git push -u origin feature/BY-733-subject-segments
gh pr create --base dev --title "[feat] BY-733 세션 과목 시간을 구간 계약으로 교체하고 서버가 계산" --body-file <(cat <<'EOF'
(.github/pull_request_template.md 절 구조 그대로, 절마다 짧게. 요약 / 변경 내용 / 계약 변경(subjectTimes→subjectSegments, V20, 배포 전 교체) / 테스트(./gradlew check, 단위 11·API 8) / 문서(ADR-0023) / 리뷰 포인트(절삭·자정·0초, Codex 지적 처리). attribution 푸터 없음)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
```

`gh auth status`가 `sangjaekwon`인지 먼저 확인한다.

- [ ] **Step 2: CI 확인·머지**

`gh pr checks --watch`는 체크 등록 전이면 즉시 끝난다(메모: pr-merge-flow-gotchas). `gh run list --branch feature/BY-733-subject-segments --limit 3`로 런이 등록된 걸 본 뒤 watch하고, 초록이면 `gh pr merge --merge`(merge commit·브랜치 유지 관례).

- [ ] **Step 3: 티켓 완료·FE 전달**

- BY-733을 완료(transition 41)로 전환한다.
- BY-735에 FE 담당 멘션 댓글(ADF mention, 허원일 `712020:1af168ed-b485-495b-bb0f-56f09b606707`): 계약 변경 요점(subjectTimes 제거 → subjectSegments{subjectId,startedAt,endedAt}, 응답 subjectSegments에 서버 계산 studySec/focusSec, 복구 응답 동일 모양, dev 배포 뒤 Swagger 확인) — 상세는 ADR-0023 링크.
- 메모리 `subject-order-color-task-done-by724.md`에 BY-733 결과(교체 완료·V20·후속 BY-734)를 한 줄 갱신한다.
