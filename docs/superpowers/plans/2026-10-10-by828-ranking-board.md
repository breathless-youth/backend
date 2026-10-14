# 랭킹 집계 엔진과 랭킹판 조회 API (BY-828 PR ①) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 확정 세션과 진행 중 스냅샷으로 모든 랭킹판(순공·집중률·시간대·명예의 전당)의 순위를 계산해 `GET /api/rankings/board`로 내려준다.

**Architecture:** 세션 저장 때 조각 순공을 시간대 구간별로 나눠 `study_session_slot`에 함께 둔다. 조회 때는 `studysession`이 제공하는
집계 쿼리(`RankingSource`)로 판 전체 순위표를 만들고 진행 중 draft 조각을 더해 판·기간 단위로 10초(명예의 전당·지난 기간 60초)
메모리 캐시한다. 요청마다 캐시된 순위표를 요청 시각까지 올리고, 새로 읽은 내 줄을 끼워 시상대·앞뒤·차이·상위 %를 만든다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL 17, Flyway, Spring Data JPA(Hibernate 7), `NamedParameterJdbcTemplate`, Jackson 3
(`tools.jackson`), JUnit 5 + AssertJ, `MockMvcTester`, Testcontainers 2.

**Spec:** `docs/superpowers/specs/2026-10-10-by828-ranking-design.md` (§3 집계 규칙, §4 진행 중 세션, §5.1 V26, §6 엔진, §7.1 API)

## Global Constraints

- 새 의존성 없음. `SecurityConfig` 변경 없음(`/api/rankings/**`는 기본 인증 `anyRequest`에 걸린다).
- 새 엔드포인트는 `version = "1"`. 테스트는 `.header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)`를 직접 싣는다 — `asUser`는 기본으로 2를 싣는다.
- 기간 경계는 KST. 일간 00시·주간 월 00시·월간 1일 00시 마감, 심야(`NIGHT`)판만 04시 마감이고 `(지금 − 4시간)`의 날짜가 기준.
- 시간대 구간: `DAWN` 04–07 · `MORNING` 07–12 · `AFTERNOON` 12–18 · `EVENING` 18–22 · `NIGHT` 22–04(시작한 날 귀속).
- 집계 기준: 순공 1분 미만 조각 제외(`StudySessionThresholds.MIN_LIST_FOCUS_SEC`), 연속 일수는 세션 10분(`MIN_STREAK_FOCUS_SEC`), 오늘(KST)까지만.
- 탈퇴 제외: SQL `u.status IS DISTINCT FROM 'DELETE'`.
- 정렬 키: 값 내림차순 → 도달 시각 오름차순 → userId 오름차순. 공동 순위 없음.
- 상위 % = `max(1, ceil(순위 × 100 ÷ 참가자 수))`(정수 올림). 목표 구간 1·5·10·20·30·50%.
- 집중률 참가: 주간 순공 36000초, 월간 108000초 이상. 집중률은 원값으로 정렬, 응답은 소수 1자리.
- 집중 중: `last_seen_at`이 60초 이내 + 마지막 이벤트 `endedAt ≠ reportedAt`. 순공·시간대 판만 `focusing`을 켠다.
- 응답에 다른 사용자의 `userId`를 싣지 않는다.
- 캐시 TTL: 진행 중 기간 판·draft 조각 10초, 명예의 전당·지난 기간 60초.
- checkstyle(테스트 포함): 파일 400줄, 메서드 60줄, 순환 복잡도 10, 파라미터 7개 이하. 포맷은 `./gradlew spotlessApply`.
- CLAUDE.md: DTO는 record, 생성자 주입만, `@Data`·`@Profile` 금지, 커밋은 `./gradlew check` 통과 상태에서만.
- 퀴즈 게이트(CLAUDE.md 7번)는 Task 11에서 push·PR 전에 한 번 한다 — 태스크별 커밋은 로컬 브랜치 안의 검증 단위다.

## Review Focus

1. **집중 중 연장이 시간대 경계를 넘을 때** — 06:59:40 스냅샷을 07:00:20에 늘리면 새 구간(오전)에 20초가 들어가야 한다. → Task 5 테스트.
2. **참가자가 1~4명인 판** — 앞뒤 창·`above`·`below`·상위 %가 경계에서 깨지지 않아야 한다(혼자면 상위 100%). → Task 7 테스트.
3. **진행 중 draft만 있는 탈퇴자** — 확정 집계엔 없고 draft만 있는 탈퇴자가 닉네임 조회에서 걸러져야 한다. → Task 8 테스트.
4. **같은 값(동점)** — 먼저 도달한 사람이 앞, 도달 시각도 같으면 userId가 작은 쪽이 앞. → Task 7 테스트.
5. **캐시에 남은 내 옛 줄** — 세션 직후 캐시(10초) 안에서 조회해도 내 값·순위가 새 값이어야 하고 내가 두 번 나오면 안 된다. → Task 7·9 테스트.

## 실행 전 준비

- [ ] 브랜치 확인: `git branch --show-current` → `feature/BY-819-ranking` (spec 커밋 `4087f20`이 있어야 한다). 아니면 `git checkout feature/BY-819-ranking`.
- [ ] 마이그레이션 번호 확인: `ls src/main/resources/db/migration | sort -V | tail -1` → `V25__user_dday_title_15.sql`. dev에 V26이 새로 생겼으면 이 계획의 V26을 다음 번호로 바꾼다.
- [ ] 지라 BY-828을 진행 중으로 전환한다 (Atlassian MCP `transitionJiraIssue`, cloudId `5a4a3f92-f903-413f-ab27-3387d26d67a4`, 전이 ID `21`).

## 파일 구조

| 파일 | 책임 |
|---|---|
| `studysession/entity/TimeSlot.java` | 구간 열거형, 시각 → 구간·귀속 날짜·다음 경계 |
| `studysession/entity/SessionSlot.java` | `study_session_slot` 행(값 객체) |
| `studysession/service/SlotSplitter.java` | 조각 하나를 구간 경계로 잘라 순공 배분(순수 로직) |
| `studysession/service/StudySessionSplitter.java` (수정) | 조각마다 구간 행을 붙임, `focusShare` 패키지 공개 |
| `studysession/entity/StudySession.java` (수정) | `slots` 값 컬렉션 |
| `resources/db/migration/V26__study_session_slot.sql` | 구간 테이블 + `stat_date` 인덱스 |
| `studysession/service/SessionSlotBackfill.java` | 기동 때 이번 주 구간 행 백필 |
| `studysession/repository/StudySessionRepository.java` (수정) | 백필 대상 조회 |
| `studysession/dto/RankingTotalRow·RankingDaysRow·RankingStreakRow·LivePiece.java` | 집계·진행 중 조각 행 |
| `studysession/repository/StudySessionRankingQueries.java` | 전체 사용자 집계 SQL |
| `studysession/service/ActiveStudySessionService.java` (수정) | draft → 진행 중 조각 |
| `studysession/service/RankingSource.java` | ranking 도메인이 세션 데이터를 읽는 유일한 입구 |
| `ranking/RankingBoardType·RankingPeriod·RankingBoard·RankingCalendar.java` | 판 종류·조합 검증·기간 달력 |
| `ranking/engine/RankingEntry·RankedEntry·Standings·Placement·StreakGroup·Tiers.java` | 순위표와 배치(순수 로직) |
| `ranking/engine/LiveSnapshot·RateTotals·BoardView.java` | 엔진 값 객체 |
| `ranking/engine/StandingsCalculator.java` | 확정 합계 + 진행 중 조각 → 줄 목록 |
| `ranking/engine/StandingsCache.java` | 키별 TTL 캐시(동시 요청 1회 계산) |
| `ranking/engine/StandingsProvider.java` | 캐시된 순위표 + 새로 읽은 내 줄 |
| `ranking/dto/RankingBoardResponse.java` | 응답 record(중첩 record 포함) |
| `ranking/service/BoardValues·BoardExtras·RankingBoardService.java` | 응답 조립 |
| `ranking/controller/RankingController.java` | `GET /api/rankings/board` |
| `docs/adr/0028-ranking-aggregate-on-read.md` | ADR |

모든 Java 경로의 앞부분은 `src/main/java/project/study/`(테스트는 `src/test/java/project/study/`)다.

---

### Task 1: 시간대 구간과 구간 분할기

**Files:**
- Create: `src/main/java/project/study/studysession/entity/TimeSlot.java`
- Create: `src/main/java/project/study/studysession/entity/SessionSlot.java`
- Create: `src/main/java/project/study/studysession/service/SlotSplitter.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionSplitter.java` (`focusShare`의 `private` 제거)
- Test: `src/test/java/project/study/studysession/entity/TimeSlotTest.java`
- Test: `src/test/java/project/study/studysession/service/SlotSplitterTest.java`

**Interfaces:**
- Consumes: `StudySessionSplitter.computeSegmentWeights(List<Instant> cuts, List<StatusEvent> sorted)`, `StudySessionSplitter.SegmentWeights`
- Produces:
  - `enum TimeSlot { DAWN, MORNING, AFTERNOON, EVENING, NIGHT }` + `static TimeSlot at(Instant)`, `static LocalDate slotDateOf(Instant)`, `static Instant nextBoundary(Instant)`
  - `SessionSlot(TimeSlot slot, LocalDate slotDate, int focusSec)` — `getSlot()`, `getSlotDate()`, `getFocusSec()`, 값 동등성
  - `SlotSplitter.split(Instant start, Instant end, int focusSec, List<StatusEvent> events): List<SessionSlot>` (패키지 공개)
  - `StudySessionSplitter.focusShare(SegmentWeights, int segment, long value): long` (패키지 공개로 변경)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/entity/TimeSlotTest.java`:

```java
package project.study.studysession.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class TimeSlotTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, KST).toInstant();
    }

    @Test
    void 구간은_시작을_포함하고_끝을_포함하지_않는다() {
        assertThat(TimeSlot.at(at(10, 4, 0))).isEqualTo(TimeSlot.DAWN);
        assertThat(TimeSlot.at(at(10, 3, 59))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 7, 0))).isEqualTo(TimeSlot.MORNING);
        assertThat(TimeSlot.at(at(10, 12, 0))).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(TimeSlot.at(at(10, 18, 0))).isEqualTo(TimeSlot.EVENING);
        assertThat(TimeSlot.at(at(10, 22, 0))).isEqualTo(TimeSlot.NIGHT);
    }

    @Test
    void 자정_뒤_4시_전은_전날_심야다() {
        assertThat(TimeSlot.slotDateOf(at(11, 3, 59))).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(TimeSlot.slotDateOf(at(11, 4, 0))).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(TimeSlot.slotDateOf(at(10, 23, 0))).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void 다음_경계는_밤_10시_뒤면_다음날_4시다() {
        assertThat(TimeSlot.nextBoundary(at(10, 23, 0))).isEqualTo(at(11, 4, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 7, 0))).isEqualTo(at(10, 12, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 0, 0))).isEqualTo(at(10, 4, 0));
    }
}
```

`src/test/java/project/study/studysession/service/SlotSplitterTest.java`:

```java
package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.TimeSlot;

class SlotSplitterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, KST).toInstant();
    }

    private static LocalDate date(int day) {
        return LocalDate.of(2026, 10, day);
    }

    @Test
    void 구간_하나_안이면_통째로_그_구간이다() {
        assertThat(SlotSplitter.split(at(10, 9, 0), at(10, 10, 0), 3000, List.of()))
                .containsExactly(new SessionSlot(TimeSlot.MORNING, date(10), 3000));
    }

    @Test
    void 경계를_넘으면_길이_비율로_나눈다() {
        assertThat(SlotSplitter.split(at(10, 6, 30), at(10, 7, 30), 3600, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.DAWN, date(10), 1800), new SessionSlot(TimeSlot.MORNING, date(10), 1800));
    }

    @Test
    void 비공부_이벤트_시간은_배분_가중치에서_빠진다() {
        // 06:30~07:30 중 07:00~07:20 PHONE → 이벤트를 뺀 길이는 새벽 1800초 · 오전 600초
        List<StatusEvent> events = List.of(new StatusEvent(EventStatus.PHONE, at(10, 7, 0), at(10, 7, 20)));

        assertThat(SlotSplitter.split(at(10, 6, 30), at(10, 7, 30), 2400, events))
                .containsExactly(
                        new SessionSlot(TimeSlot.DAWN, date(10), 1800), new SessionSlot(TimeSlot.MORNING, date(10), 600));
    }

    @Test
    void 배분_합은_항상_조각_순공과_같다() {
        List<SessionSlot> slots = SlotSplitter.split(at(10, 5, 0), at(10, 13, 0), 10_001, List.of());

        assertThat(slots)
                .extracting(SessionSlot::getSlot)
                .containsExactly(TimeSlot.DAWN, TimeSlot.MORNING, TimeSlot.AFTERNOON);
        assertThat(slots.stream().mapToInt(SessionSlot::getFocusSec).sum()).isEqualTo(10_001);
    }

    @Test
    void 새벽_4시_전의_심야는_전날에_귀속된다() {
        assertThat(SlotSplitter.split(at(10, 2, 0), at(10, 5, 0), 10_800, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(9), 7200), new SessionSlot(TimeSlot.DAWN, date(10), 3600));
    }

    @Test
    void 밤_10시_뒤의_심야는_그날에_귀속된다() {
        assertThat(SlotSplitter.split(at(10, 21, 0), at(11, 0, 0), 10_800, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.EVENING, date(10), 3600), new SessionSlot(TimeSlot.NIGHT, date(10), 7200));
    }

    @Test
    void 순공이_0이면_행이_없다() {
        assertThat(SlotSplitter.split(at(10, 9, 0), at(10, 10, 0), 0, List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.entity.TimeSlotTest" --tests "project.study.studysession.service.SlotSplitterTest"`
Expected: 컴파일 실패 (`TimeSlot`, `SessionSlot`, `SlotSplitter` 없음)

- [ ] **Step 3: 구현**

`src/main/java/project/study/studysession/entity/TimeSlot.java`:

```java
package project.study.studysession.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 시간대 랭킹의 구간 (BY-828). 경계는 KST 04·07·12·18·22시이고 구간은 [시작, 끝) 반개구간이다.
 * 심야(22–04)만 시작한 날에 귀속한다 — D일 22:00 ~ D+1일 04:00이 D일 심야다.
 */
public enum TimeSlot {
    DAWN,
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int[] BOUNDARY_HOURS = {4, 7, 12, 18, 22};

    /** 시각이 속한 구간. */
    public static TimeSlot at(Instant instant) {
        int hour = instant.atZone(KST).getHour();
        if (hour < 4 || hour >= 22) {
            return NIGHT;
        }
        if (hour < 7) {
            return DAWN;
        }
        if (hour < 12) {
            return MORNING;
        }
        return hour < 18 ? AFTERNOON : EVENING;
    }

    /** 시각이 속한 구간의 귀속 날짜 — 심야의 00~04시는 전날이다. */
    public static LocalDate slotDateOf(Instant instant) {
        ZonedDateTime kst = instant.atZone(KST);
        return kst.getHour() < 4 ? kst.toLocalDate().minusDays(1) : kst.toLocalDate();
    }

    /** instant보다 뒤인 가장 가까운 구간 경계. */
    public static Instant nextBoundary(Instant instant) {
        LocalDate date = instant.atZone(KST).toLocalDate();
        for (int hour : BOUNDARY_HOURS) {
            Instant boundary = date.atTime(hour, 0).atZone(KST).toInstant();
            if (boundary.isAfter(instant)) {
                return boundary;
            }
        }
        return date.plusDays(1).atTime(BOUNDARY_HOURS[0], 0).atZone(KST).toInstant();
    }
}
```

`src/main/java/project/study/studysession/entity/SessionSlot.java`:

```java
package project.study.studysession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 세션 조각의 시간대 구간별 순공 (BY-828) — study_session_slot 한 행. 조각 순공을 구간 경계로 배분한 서버 파생값이라
 * 세션과 함께 저장·대체·삭제된다(완료 할 일처럼 값 컬렉션).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionSlot {

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false)
    private TimeSlot slot;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "focus_sec", nullable = false)
    private int focusSec;

    public SessionSlot(TimeSlot slot, LocalDate slotDate, int focusSec) {
        this.slot = slot;
        this.slotDate = slotDate;
        this.focusSec = focusSec;
    }
}
```

`src/main/java/project/study/studysession/service/SlotSplitter.java`:

```java
package project.study.studysession.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.TimeSlot;

/**
 * 자정 분할 조각 하나를 시간대 구간 경계(KST 04·07·12·18·22시)로 다시 잘라 조각 순공을 구간별로 배분하는 순수 로직 (BY-828).
 * 배분은 자정 분할과 같은 규칙이다 — 이벤트를 뺀 길이 비율로 나누고 마지막 구간이 나머지를 가져가 합이 조각 순공과 같다.
 * 0초 몫은 행을 만들지 않는다.
 */
final class SlotSplitter {

    private SlotSplitter() {}

    static List<SessionSlot> split(Instant start, Instant end, int focusSec, List<StatusEvent> events) {
        if (focusSec <= 0 || !start.isBefore(end)) {
            return List.of();
        }
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        List<Instant> cuts = cuts(start, end);
        StudySessionSplitter.SegmentWeights weights = StudySessionSplitter.computeSegmentWeights(cuts, sorted);
        if (weights.totalFocusActiveSec() == 0 && weights.totalStudyActiveSec() == 0) {
            // 조각 전체가 일시정지인데 자정 배분의 나머지로 순공이 남은 극단값 — 나눌 기준이 없어 시작 구간에 둔다
            return List.of(new SessionSlot(TimeSlot.at(start), TimeSlot.slotDateOf(start), focusSec));
        }
        int count = cuts.size() - 1;
        List<SessionSlot> slots = new ArrayList<>();
        long allocated = 0;
        for (int i = 0; i < count; i++) {
            long share = i == count - 1 ? focusSec - allocated : StudySessionSplitter.focusShare(weights, i, focusSec);
            allocated += share;
            if (share > 0) {
                Instant segmentStart = cuts.get(i);
                slots.add(new SessionSlot(TimeSlot.at(segmentStart), TimeSlot.slotDateOf(segmentStart), (int) share));
            }
        }
        return slots;
    }

    private static List<Instant> cuts(Instant start, Instant end) {
        List<Instant> cuts = new ArrayList<>();
        cuts.add(start);
        for (Instant boundary = TimeSlot.nextBoundary(start);
                boundary.isBefore(end);
                boundary = TimeSlot.nextBoundary(boundary)) {
            cuts.add(boundary);
        }
        cuts.add(end);
        return cuts;
    }
}
```

`StudySessionSplitter.java`에서 `focusShare` 선언의 `private`만 지운다:

```java
    /** focusSec 계열의 조각 몫 — 이벤트를 제외한 조각 길이 비율. 그 합이 0(전 구간이 이벤트)이면 studySec 비율로 대체한다. */
    static long focusShare(SegmentWeights weights, int segment, long value) {
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.entity.TimeSlotTest" --tests "project.study.studysession.service.SlotSplitterTest"`
Expected: PASS (10 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/studysession/entity/TimeSlot.java src/main/java/project/study/studysession/entity/SessionSlot.java src/main/java/project/study/studysession/service/SlotSplitter.java src/main/java/project/study/studysession/service/StudySessionSplitter.java src/test/java/project/study/studysession/entity/TimeSlotTest.java src/test/java/project/study/studysession/service/SlotSplitterTest.java
git commit -m "feat: 세션 조각을 시간대 구간별로 나누는 분할기를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 세션 저장 때 구간 행을 함께 저장 (V26)

**Files:**
- Create: `src/main/resources/db/migration/V26__study_session_slot.sql`
- Modify: `src/main/java/project/study/studysession/entity/StudySession.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionSplitter.java` (`buildSessions`)
- Test: `src/test/java/project/study/studysession/SessionSlotPersistTest.java`

**Interfaces:**
- Consumes: Task 1의 `SlotSplitter.split`, `SessionSlot`
- Produces: `StudySession.getSlots(): Set<SessionSlot>`, `StudySession.attachSlots(Collection<SessionSlot>)`. `StudySessionSplitter.buildSessions`가 만드는 모든 조각에 구간 행이 붙는다(제출·자동 확정·스냅샷 검증·진행 중 조각 공통).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/SessionSlotPersistTest.java`:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.service.StudySessionService;

/** 세션을 저장하면 조각마다 시간대 구간별 순공이 study_session_slot에 함께 저장된다 (BY-828). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionSlotPersistTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private StudySessionService studySessionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "slot-" + UUID.randomUUID());
    }

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, KST).toInstant();
    }

    private void submit(Instant start, Instant end, int focusSec, boolean autoFinalized) {
        int length = (int) Duration.between(start, end).toSeconds();
        studySessionService.create(
                userId, new StudySessionCreateRequest(start, end, length, focusSec, List.of(), null, null), autoFinalized);
    }

    private List<String> slotRows() {
        return jdbc.queryForList(
                """
                SELECT sl.slot || ' ' || sl.slot_date || ' ' || sl.focus_sec
                FROM study_session_slot sl JOIN study_session s ON s.id = sl.session_id
                WHERE s.user_id = ?
                ORDER BY s.started_at, sl.slot_date, sl.slot""",
                String.class,
                userId);
    }

    @Test
    void 세션을_저장하면_구간별_순공도_저장된다() {
        submit(at(20, 6, 30), at(20, 7, 30), 3600, false);

        assertThat(slotRows()).containsExactly("DAWN 2026-09-20 1800", "MORNING 2026-09-20 1800");
    }

    @Test
    void 자정을_넘는_세션은_두_조각_모두_시작한_날의_심야다() {
        submit(at(20, 23, 0), at(21, 1, 0), 7200, false);

        assertThat(slotRows()).containsExactly("NIGHT 2026-09-20 3600", "NIGHT 2026-09-20 3600");
    }

    @Test
    void 자동_확정본이_대체되면_구간_행도_새_값으로_바뀐다() {
        submit(at(22, 9, 0), at(22, 9, 30), 1800, true);
        submit(at(22, 9, 0), at(22, 10, 0), 3600, false);

        assertThat(slotRows()).containsExactly("MORNING 2026-09-22 3600");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.SessionSlotPersistTest"`
Expected: FAIL — `relation "study_session_slot" does not exist`

- [ ] **Step 3: 구현**

`src/main/resources/db/migration/V26__study_session_slot.sql`:

```sql
-- BY-828: 시간대 랭킹용 — 세션 조각의 순공을 시간대 구간(KST 04·07·12·18·22시)별로 나눠 둔다.
-- 세션 저장 트랜잭션에서 함께 쓰는 파생값이고(과목 구간과 같은 방식, ADR-0023 §6) 세션과 함께 대체·삭제된다
CREATE TABLE study_session_slot (
    session_id BIGINT  NOT NULL REFERENCES study_session (id) ON DELETE CASCADE,
    slot       VARCHAR NOT NULL,   -- DAWN·MORNING·AFTERNOON·EVENING·NIGHT
    slot_date  DATE    NOT NULL,   -- 심야(22–04)는 시작한 날
    focus_sec  INT     NOT NULL,
    PRIMARY KEY (session_id, slot, slot_date)
);
CREATE INDEX idx_study_session_slot_date ON study_session_slot (slot_date, slot) INCLUDE (session_id, focus_sec);

-- 랭킹은 전체 사용자의 기간 합계를 낸다 — 기존 (user_id, stat_date) 인덱스로는 날짜 범위 스캔이 안 된다
CREATE INDEX idx_study_session_stat_date ON study_session (stat_date) INCLUDE (user_id, focus_sec, study_sec, ended_at);
```

`StudySession.java` — `completedTaskIds` 필드 아래에 추가 (import `project.study.studysession.entity.SessionSlot`는 같은 패키지라 불필요):

```java
    // 시간대 랭킹용 구간별 순공 (BY-828) — 조각을 구간 경계로 잘라 배분한 파생값. 완료 할 일처럼 값 컬렉션이라
    // 세션과 함께 저장·대체·삭제된다
    @ElementCollection
    @CollectionTable(name = "study_session_slot", joinColumns = @JoinColumn(name = "session_id", nullable = false))
    @BatchSize(size = 64)
    private Set<SessionSlot> slots = new HashSet<>();
```

`attachCompletedTasks` 아래에 추가:

```java
    /** 조각의 구간별 순공을 붙인다 — 분할 직후 서비스(또는 백필)만 호출한다. */
    public void attachSlots(Collection<SessionSlot> slots) {
        this.slots = new HashSet<>(slots);
    }
```

`StudySessionSplitter.buildSessions`의 루프에서 `session.attachCompletedTasks(completedBySegment.get(i));` 바로 아래에 추가:

```java
            session.attachSlots(SlotSplitter.split(
                    cuts.get(i), cuts.get(i + 1), (int) segmentFocusSec, weights.segmentEvents().get(i)));
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.*"`
Expected: PASS (기존 세션 테스트 포함 전부)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/resources/db/migration/V26__study_session_slot.sql src/main/java/project/study/studysession/entity/StudySession.java src/main/java/project/study/studysession/service/StudySessionSplitter.java src/test/java/project/study/studysession/SessionSlotPersistTest.java
git commit -m "feat: 세션을 저장할 때 시간대 구간별 순공을 함께 저장한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 이번 주 세션 구간 행 백필

**Files:**
- Create: `src/main/java/project/study/studysession/service/SessionSlotBackfill.java`
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRepository.java`
- Test: `src/test/java/project/study/studysession/service/SessionSlotBackfillTest.java`

**Interfaces:**
- Consumes: `SlotSplitter.split`, `StudySession.attachSlots`
- Produces: `StudySessionRepository.findSlotlessSince(LocalDate from): List<StudySession>`, 기동 러너 `SessionSlotBackfill`(`run(ApplicationArguments)`)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/service/SessionSlotBackfillTest.java`:

```java
package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;

/** 기동 때 이번 주 세션 중 구간 행이 없는 것을 채운다 — 멱등이고 지난 주 이전은 건드리지 않는다 (BY-828). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionSlotBackfillTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private SessionSlotBackfill backfill;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "backfill-" + UUID.randomUUID());
    }

    /** 구간 행 없이 세션 행만 넣는다 — V26 이전에 저장된 세션과 같은 모양 */
    private long legacySession(LocalDate date) {
        Instant start = date.atTime(9, 0).atZone(KST).toInstant();
        Instant end = start.plusSeconds(1800);
        return jdbc.queryForObject(
                """
                INSERT INTO study_session
                    (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, auto_finalized)
                VALUES (?, ?, ?, ?, ?, 1800, 1800, false) RETURNING id""",
                Long.class,
                userId,
                date,
                start.atOffset(ZoneOffset.UTC),
                start.atOffset(ZoneOffset.UTC),
                end.atOffset(ZoneOffset.UTC));
    }

    private int slotCount(long sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM study_session_slot WHERE session_id = ?", Integer.class, sessionId);
    }

    @Test
    void 이번_주_세션의_빠진_구간_행만_채우고_다시_돌려도_그대로다() {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        long thisWeek = legacySession(today);
        long old = legacySession(today.minusDays(20));

        backfill.run(new DefaultApplicationArguments());
        backfill.run(new DefaultApplicationArguments());

        assertThat(slotCount(thisWeek)).isEqualTo(1);
        assertThat(slotCount(old)).isZero();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.service.SessionSlotBackfillTest"`
Expected: 컴파일 실패 (`SessionSlotBackfill` 없음)

- [ ] **Step 3: 구현**

`StudySessionRepository.java`에 추가 (import `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.time.LocalDate`는 이미 있음):

```java
    // BY-828 백필 — 시간대 행이 없는 최근 세션. 순공 0·종료 시각 없는(레거시) 세션은 행이 생길 수 없어 뺀다
    @Query("""
            select s from StudySession s
            where s.statDate >= :from and s.focusSec > 0 and s.endedAt is not null and s.slots is empty""")
    List<StudySession> findSlotlessSince(@Param("from") LocalDate from);
```

`src/main/java/project/study/studysession/service/SessionSlotBackfill.java`:

```java
package project.study.studysession.service;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

/**
 * V26 이전에 저장된 세션의 시간대 구간 행을 기동 때 채운다 (BY-828). 시간대 랭킹은 일·주뿐이라 이번 주(전주 일요일
 * 심야 포함) 세션만 있으면 된다. 출시 뒤엔 모든 새 세션에 행이 있어 조회 한 번으로 끝난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSlotBackfill implements ApplicationRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StudySessionRepository studySessionRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 실패해도 기동은 막지 않는다 — 이번 주 시간대 값이 비는 것보다 서버가 안 뜨는 게 더 나쁘다. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer filled = transactionTemplate.execute(status -> backfill());
            if (filled != null && filled > 0) {
                log.info("시간대 구간 백필: {}개 세션", filled);
            }
        } catch (RuntimeException e) {
            log.error("시간대 구간 백필 실패", e);
            Sentry.captureException(e);
        }
    }

    /** 구간 행이 없는 세션만 조회되므로 다시 돌려도 같은 결과다. 트랜잭션 안에서만 부른다(변경 감지로 저장). */
    private int backfill() {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        LocalDate from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(1);
        List<StudySession> sessions = studySessionRepository.findSlotlessSince(from);
        sessions.forEach(session -> session.attachSlots(SlotSplitter.split(
                session.getStartedAt(), session.getEndedAt(), session.getFocusSec(), session.getEvents())));
        return sessions.size();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.service.SessionSlotBackfillTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/studysession/service/SessionSlotBackfill.java src/main/java/project/study/studysession/repository/StudySessionRepository.java src/test/java/project/study/studysession/service/SessionSlotBackfillTest.java
git commit -m "feat: 이번 주 세션의 시간대 구간 행을 기동 때 채운다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 전체 사용자 집계 쿼리와 RankingSource

**Files:**
- Create: `src/main/java/project/study/studysession/dto/RankingTotalRow.java`
- Create: `src/main/java/project/study/studysession/dto/RankingDaysRow.java`
- Create: `src/main/java/project/study/studysession/dto/RankingStreakRow.java`
- Create: `src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java`
- Create: `src/main/java/project/study/studysession/service/RankingSource.java`
- Test: `src/test/java/project/study/studysession/StudySessionRankingQueriesTest.java`

**Interfaces:**
- Consumes: V26 테이블, `StudySessionThresholds`
- Produces:
  - `record RankingTotalRow(long userId, String nickname, long focusSec, long studySec, Instant achievedAt)`
  - `record RankingDaysRow(long userId, String nickname, int days, Instant achievedAt)`
  - `record RankingStreakRow(long userId, String nickname, int days, LocalDate startDate, LocalDate endDate, Instant achievedAt)`
  - `RankingSource`(public `@Service`): `periodTotals(LocalDate from, LocalDate to, Long userId)`, `slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId)` → `List<RankingTotalRow>`; `studyDays(LocalDate today, Long userId)` → `List<RankingDaysRow>`; `maxStreaks(LocalDate today, Long userId)` → `List<RankingStreakRow>`; `activeNicknames(Collection<Long>)` → `Map<Long, String>`. `userId`가 null이면 전체, 아니면 그 사용자 한 줄.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/StudySessionRankingQueriesTest.java` — 다른 테스트와 컨테이너를 같이 쓰므로 아무도 안 쓰는 2031년 날짜를 쓰고, 결과는 이 테스트가 만든 사용자로만 거른다:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StudySessionRankingQueriesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate FROM = LocalDate.of(2031, 3, 2);
    private static final LocalDate TO = LocalDate.of(2031, 3, 8);
    private static final LocalDate TODAY = LocalDate.of(2031, 3, 31);

    @Autowired
    private RankingSource source;

    @Autowired
    private JdbcTemplate jdbc;

    private long user() {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "q-" + UUID.randomUUID());
    }

    private String nickname(long userId) {
        return jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, userId);
    }

    private static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(KST).toInstant();
    }

    /** 세션 행을 직접 넣는다 — 2031년은 미래라 제출 API 검증을 못 지난다. 반환은 세션 id */
    private long session(long userId, LocalDate date, int hour, int minutes, int focusSec) {
        Instant start = at(date, hour);
        return jdbc.queryForObject(
                """
                INSERT INTO study_session
                    (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, auto_finalized)
                VALUES (?, ?, ?, ?, ?, ?, ?, false) RETURNING id""",
                Long.class,
                userId,
                date,
                start.atOffset(ZoneOffset.UTC),
                start.atOffset(ZoneOffset.UTC),
                start.plusSeconds(minutes * 60L).atOffset(ZoneOffset.UTC),
                minutes * 60,
                focusSec);
    }

    private void slot(long sessionId, TimeSlot slot, LocalDate date, int focusSec) {
        jdbc.update(
                "INSERT INTO study_session_slot (session_id, slot, slot_date, focus_sec) VALUES (?, ?, ?, ?)",
                sessionId,
                slot.name(),
                date,
                focusSec);
    }

    @Test
    void 기간_합계는_일분_미만_조각과_기간_밖과_탈퇴자를_빼고_사용자별로_낸다() {
        long a = user();
        long b = user();
        long gone = user();
        session(a, FROM.plusDays(1), 9, 60, 3000);
        session(a, FROM.plusDays(2), 9, 1, 59);
        session(a, LocalDate.of(2031, 3, 20), 9, 30, 1000);
        session(b, FROM.plusDays(2), 10, 30, 1800);
        session(gone, FROM.plusDays(1), 9, 60, 3000);
        jdbc.update("UPDATE users SET status = 'DELETE' WHERE id = ?", gone);

        List<RankingTotalRow> rows = source.periodTotals(FROM, TO, null);

        assertThat(rows)
                .filteredOn(r -> Set.of(a, b, gone).contains(r.userId()))
                .extracting(RankingTotalRow::userId, RankingTotalRow::focusSec, RankingTotalRow::studySec, RankingTotalRow::achievedAt)
                .containsExactlyInAnyOrder(
                        tuple(a, 3000L, 3600L, at(FROM.plusDays(1), 10)), tuple(b, 1800L, 1800L, at(FROM.plusDays(2), 10).plusSeconds(1800)));
        assertThat(source.periodTotals(FROM, TO, a)).extracting(RankingTotalRow::nickname).containsExactly(nickname(a));
    }

    @Test
    void 시간대_합계는_그_구간_행만_더한다() {
        long a = user();
        long sessionId = session(a, FROM.plusDays(1), 6, 60, 3600);
        slot(sessionId, TimeSlot.DAWN, FROM.plusDays(1), 2400);
        slot(sessionId, TimeSlot.MORNING, FROM.plusDays(1), 1200);

        assertThat(source.slotTotals(TimeSlot.MORNING, FROM, TO, a))
                .extracting(RankingTotalRow::focusSec)
                .containsExactly(1200L);
    }

    @Test
    void 누적_일수는_오늘까지_일분_이상인_날을_세고_마지막_날에_처음_넘긴_시각이_도달_시각이다() {
        long a = user();
        session(a, LocalDate.of(2031, 3, 3), 9, 30, 1000);
        session(a, LocalDate.of(2031, 3, 4), 9, 1, 59);
        session(a, LocalDate.of(2031, 3, 20), 9, 30, 1000);
        session(a, LocalDate.of(2031, 3, 20), 14, 30, 1000);
        session(a, LocalDate.of(2031, 4, 2), 9, 30, 1000);

        assertThat(source.studyDays(TODAY, a))
                .extracting(RankingDaysRow::days, RankingDaysRow::achievedAt)
                .containsExactly(tuple(2, at(LocalDate.of(2031, 3, 20), 9).plusSeconds(1800)));
    }

    @Test
    void 최장_연속은_가장_먼저_찍은_최장_구간이고_십분_미만_날은_끊는다() {
        long c = user();
        for (int day : new int[] {1, 2, 3, 5, 6, 7}) {
            session(c, LocalDate.of(2031, 3, day), 9, 15, 900);
        }
        session(c, LocalDate.of(2031, 3, 4), 9, 15, 500);

        assertThat(source.maxStreaks(TODAY, c))
                .extracting(RankingStreakRow::days, RankingStreakRow::startDate, RankingStreakRow::endDate, RankingStreakRow::achievedAt)
                .containsExactly(tuple(
                        3,
                        LocalDate.of(2031, 3, 1),
                        LocalDate.of(2031, 3, 3),
                        at(LocalDate.of(2031, 3, 3), 9).plusSeconds(900)));
    }

    @Test
    void 닉네임은_탈퇴하지_않은_사용자만_읽는다() {
        long a = user();
        long gone = user();
        jdbc.update("UPDATE users SET status = 'DELETE' WHERE id = ?", gone);

        assertThat(source.activeNicknames(List.of(a, gone))).containsOnlyKeys(a);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionRankingQueriesTest"`
Expected: 컴파일 실패 (`RankingSource` 등 없음)

- [ ] **Step 3: 구현**

`src/main/java/project/study/studysession/dto/RankingTotalRow.java`:

```java
package project.study.studysession.dto;

import java.time.Instant;

/** 랭킹 집계 한 줄 (BY-828) — 사용자별 순공·총공부 합과 그 값을 만든 마지막 조각의 종료 시각(동점 판정용). */
public record RankingTotalRow(long userId, String nickname, long focusSec, long studySec, Instant achievedAt) {}
```

`src/main/java/project/study/studysession/dto/RankingDaysRow.java`:

```java
package project.study.studysession.dto;

import java.time.Instant;

/** 누적 공부일 한 줄 (BY-828) — achievedAt은 마지막 공부일에 기준(1분)을 처음 넘긴 조각의 종료 시각. */
public record RankingDaysRow(long userId, String nickname, int days, Instant achievedAt) {}
```

`src/main/java/project/study/studysession/dto/RankingStreakRow.java`:

```java
package project.study.studysession.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 최장 연속 공부일 한 줄 (BY-828) — 그 길이를 처음 찍은 구간과, 끝 날에 기준(10분)을 처음 넘긴 조각의 종료 시각. */
public record RankingStreakRow(
        long userId, String nickname, int days, LocalDate startDate, LocalDate endDate, Instant achievedAt) {}
```

`src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java`:

```java
package project.study.studysession.repository;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;
import static project.study.studysession.StudySessionThresholds.MIN_STREAK_FOCUS_SEC;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;

/**
 * 랭킹 집계 전용 읽기 쿼리 (BY-828) — 전체 사용자의 기간·구간·누적 값을 사용자별 한 줄로 낸다.
 * userId를 주면 그 사용자 한 줄만 계산한다(내 값은 캐시와 상관없이 매번 새로 읽는다). 탈퇴(DELETE) 사용자는 뺀다.
 * 기준은 다른 화면과 같다 — 순공 1분 미만 조각 제외(ADR-0009), 연속 일수는 세션 10분 기준.
 */
@Repository
@RequiredArgsConstructor
public class StudySessionRankingQueries {

    private static final String NOT_WITHDRAWN = "u.status IS DISTINCT FROM 'DELETE'";

    private static final RowMapper<RankingTotalRow> TOTAL_ROW = (rs, i) -> new RankingTotalRow(
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getLong("focus_sec"),
            rs.getLong("study_sec"),
            instant(rs, "achieved_at"));

    private final NamedParameterJdbcTemplate jdbc;

    /** 기간(stat_date) 순공·총공부 합. 도달 시각은 그 값을 만든 마지막 조각의 종료 시각이다. */
    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId) {
        String sql = """
                SELECT s.user_id, u.nickname,
                       SUM(s.focus_sec) AS focus_sec,
                       COALESCE(SUM(s.study_sec), 0) AS study_sec,
                       COALESCE(MAX(s.ended_at), MAX(s.started_at)) AS achieved_at
                FROM study_session s
                JOIN users u ON u.id = s.user_id
                WHERE s.stat_date BETWEEN :from AND :to
                  AND s.focus_sec >= :minFocusSec
                  AND %s%s
                GROUP BY s.user_id, u.nickname""".formatted(NOT_WITHDRAWN, userFilter(userId));
        return jdbc.query(sql, range(userId, from, to), TOTAL_ROW);
    }

    /** 시간대 구간 순공 합 — 구간 행은 조각 단위라 조각의 1분 기준을 그대로 건다. */
    public List<RankingTotalRow> slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId) {
        String sql = """
                SELECT s.user_id, u.nickname,
                       SUM(sl.focus_sec) AS focus_sec,
                       0 AS study_sec,
                       COALESCE(MAX(s.ended_at), MAX(s.started_at)) AS achieved_at
                FROM study_session_slot sl
                JOIN study_session s ON s.id = sl.session_id
                JOIN users u ON u.id = s.user_id
                WHERE sl.slot = :slot
                  AND sl.slot_date BETWEEN :from AND :to
                  AND s.focus_sec >= :minFocusSec
                  AND %s%s
                GROUP BY s.user_id, u.nickname""".formatted(NOT_WITHDRAWN, userFilter(userId));
        return jdbc.query(sql, range(userId, from, to).addValue("slot", slot.name()), TOTAL_ROW);
    }

    /** 누적 공부일 — 순공 1분 이상 조각이 있는 날 수(오늘까지). 누적 공부일 API와 같은 숫자다. */
    public List<RankingDaysRow> studyDays(LocalDate today, Long userId) {
        String sql = """
                WITH days AS (
                    SELECT s.user_id, s.stat_date, COALESCE(MIN(s.ended_at), MIN(s.started_at)) AS first_done
                    FROM study_session s
                    WHERE s.focus_sec >= :minFocusSec AND s.stat_date <= :today%s
                    GROUP BY s.user_id, s.stat_date
                )
                SELECT d.user_id, u.nickname, COUNT(*) AS days,
                       (ARRAY_AGG(d.first_done ORDER BY d.stat_date DESC))[1] AS achieved_at
                FROM days d
                JOIN users u ON u.id = d.user_id
                WHERE %s
                GROUP BY d.user_id, u.nickname""".formatted(userFilter(userId), NOT_WITHDRAWN);
        MapSqlParameterSource params =
                params(userId).addValue("today", today).addValue("minFocusSec", MIN_LIST_FOCUS_SEC);
        return jdbc.query(sql, params, (rs, i) -> new RankingDaysRow(
                rs.getLong("user_id"), rs.getString("nickname"), rs.getInt("days"), instant(rs, "achieved_at")));
    }

    /**
     * 최장 연속 공부일 — 세션 10분 기준 날짜를 연속 구간으로 묶어(gaps-and-islands) 가장 긴 구간을, 같으면 먼저 끝난 구간을
     * 고른다. 스트릭 API의 maxStreak와 같은 숫자다.
     */
    public List<RankingStreakRow> maxStreaks(LocalDate today, Long userId) {
        String sql = """
                WITH days AS (
                    SELECT s.user_id, s.stat_date, COALESCE(MIN(s.ended_at), MIN(s.started_at)) AS first_done
                    FROM study_session s
                    WHERE s.focus_sec >= :minFocusSec AND s.stat_date <= :today%s
                    GROUP BY s.user_id, s.stat_date
                ), runs AS (
                    SELECT user_id, stat_date, first_done,
                           stat_date - CAST(ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY stat_date) AS INT) AS grp
                    FROM days
                ), islands AS (
                    SELECT user_id, grp, COUNT(*) AS len, MIN(stat_date) AS start_date, MAX(stat_date) AS end_date
                    FROM runs
                    GROUP BY user_id, grp
                ), best AS (
                    SELECT DISTINCT ON (user_id) user_id, len, start_date, end_date
                    FROM islands
                    ORDER BY user_id, len DESC, end_date ASC
                )
                SELECT b.user_id, u.nickname, b.len AS days, b.start_date, b.end_date, r.first_done AS achieved_at
                FROM best b
                JOIN runs r ON r.user_id = b.user_id AND r.stat_date = b.end_date
                JOIN users u ON u.id = b.user_id
                WHERE %s""".formatted(userFilter(userId), NOT_WITHDRAWN);
        MapSqlParameterSource params =
                params(userId).addValue("today", today).addValue("minFocusSec", MIN_STREAK_FOCUS_SEC);
        return jdbc.query(sql, params, (rs, i) -> new RankingStreakRow(
                rs.getLong("user_id"),
                rs.getString("nickname"),
                rs.getInt("days"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                instant(rs, "achieved_at")));
    }

    /** 진행 중 조각만 있는 사용자의 닉네임 — 탈퇴자는 빠진다. */
    public Map<Long, String> activeNicknames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT u.id, u.nickname FROM users u WHERE u.id IN (:ids) AND " + NOT_WITHDRAWN;
        return jdbc
                .query(sql, new MapSqlParameterSource("ids", userIds), (rs, i) -> Map.entry(rs.getLong("id"), rs.getString("nickname")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String userFilter(Long userId) {
        return userId == null ? "" : " AND s.user_id = :userId";
    }

    private static MapSqlParameterSource params(Long userId) {
        return new MapSqlParameterSource("userId", userId);
    }

    private static MapSqlParameterSource range(Long userId, LocalDate from, LocalDate to) {
        return params(userId).addValue("from", from).addValue("to", to).addValue("minFocusSec", MIN_LIST_FOCUS_SEC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
```

`src/main/java/project/study/studysession/service/RankingSource.java`:

```java
package project.study.studysession.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.StudySessionRankingQueries;

/**
 * 랭킹(BY-828)이 세션 데이터를 읽는 유일한 입구 — 집계 쿼리·진행 중 조각·스트릭을 한곳에 모아 ranking 도메인이 세션 내부
 * 구조(리포지토리·분할기)를 직접 알지 않게 한다. metrics의 StudySessionMetricsService와 같은 자리다.
 */
@Service
@RequiredArgsConstructor
public class RankingSource {

    private final StudySessionRankingQueries queries;

    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId) {
        return queries.periodTotals(from, to, userId);
    }

    public List<RankingTotalRow> slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId) {
        return queries.slotTotals(slot, from, to, userId);
    }

    public List<RankingDaysRow> studyDays(LocalDate today, Long userId) {
        return queries.studyDays(today, userId);
    }

    public List<RankingStreakRow> maxStreaks(LocalDate today, Long userId) {
        return queries.maxStreaks(today, userId);
    }

    public Map<Long, String> activeNicknames(Collection<Long> userIds) {
        return queries.activeNicknames(userIds);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionRankingQueriesTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/studysession/dto/RankingTotalRow.java src/main/java/project/study/studysession/dto/RankingDaysRow.java src/main/java/project/study/studysession/dto/RankingStreakRow.java src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java src/main/java/project/study/studysession/service/RankingSource.java src/test/java/project/study/studysession/StudySessionRankingQueriesTest.java
git commit -m "feat: 랭킹용 전체 사용자 집계 쿼리를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 진행 중 draft를 랭킹 조각으로 나누기

**Files:**
- Create: `src/main/java/project/study/studysession/dto/LivePiece.java`
- Modify: `src/main/java/project/study/studysession/service/ActiveStudySessionService.java`
- Modify: `src/main/java/project/study/studysession/service/RankingSource.java`
- Test: `src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java`

**Interfaces:**
- Consumes: `StudySessionSplitter.computeCuts/computeSegmentWeights/buildSessions`(구간 행 포함, Task 2), `StudySessionService.streak`, `StudySessionRepository.findDistinctStatDatesBetween`
- Produces:
  - `record LivePiece(long userId, LocalDate statDate, int focusSec, int studySec, List<SessionSlot> slots, boolean latest, boolean focusing, Instant achievedAt)`
  - `ActiveStudySessionService.livePieces(Instant asOf): List<LivePiece>`, `ActiveStudySessionService.FOCUSING_WINDOW = 60초`
  - `RankingSource.livePieces(Instant asOf)`, `RankingSource.currentStreak(long userId): int`, `RankingSource.studiedDays(long userId, LocalDate from, LocalDate to): int`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java`:

```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.ActiveStudySessionService;

/** 진행 중 draft를 확정과 같은 규칙으로 나누고, 집중 중이면 마지막 수신 뒤 경과만큼 늘린다 (BY-828). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveSessionLivePiecesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private ActiveStudySessionService service;

    @Autowired
    private ActiveStudySessionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = user();
    }

    private long user() {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "live-" + UUID.randomUUID());
    }

    private static Instant at(int day, int hour, int minute, int second) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, second, 0, KST).toInstant();
    }

    /** lastSeenAt = reportedAt인 스냅샷. 총공부는 길이 전체 */
    private void draft(long owner, Instant start, Instant reportedAt, int focusSec, String events) {
        int length = (int) Duration.between(start, reportedAt).toSeconds();
        repository.upsertSnapshot(owner, start, reportedAt, reportedAt, length, focusSec, events);
    }

    private List<LivePiece> mine(Instant asOf, long owner) {
        return service.livePieces(asOf).stream().filter(p -> p.userId() == owner).toList();
    }

    @Test
    void 집중_중이면_마지막_수신_뒤_경과_시간만큼_늘려_계산한다() {
        Instant asOf = at(15, 15, 0, 0);
        draft(userId, asOf.minusSeconds(600), asOf.minusSeconds(10), 590, "[]");

        assertThat(mine(asOf, userId)).singleElement().satisfies(p -> {
            assertThat(p.focusSec()).isEqualTo(600);
            assertThat(p.studySec()).isEqualTo(600);
            assertThat(p.focusing()).isTrue();
            assertThat(p.latest()).isTrue();
            assertThat(p.achievedAt()).isEqualTo(asOf);
            assertThat(p.statDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        });
    }

    @Test
    void 진행_중인_이벤트가_있으면_집중_중이_아니고_늘리지_않는다() {
        Instant asOf = at(15, 15, 0, 0);
        Instant reported = asOf.minusSeconds(10);
        String phoneUntilNow =
                "[{\"status\":\"PHONE\",\"startedAt\":\"%s\",\"endedAt\":\"%s\"}]".formatted(reported.minusSeconds(60), reported);
        draft(userId, asOf.minusSeconds(600), reported, 530, phoneUntilNow);

        assertThat(mine(asOf, userId)).singleElement().satisfies(p -> {
            assertThat(p.focusing()).isFalse();
            assertThat(p.focusSec()).isEqualTo(530);
            assertThat(p.achievedAt()).isEqualTo(reported);
        });
    }

    @Test
    void 마지막_수신이_60초보다_오래면_집중_중이_아니다() {
        Instant asOf = at(15, 15, 0, 0);
        draft(userId, asOf.minusSeconds(600), asOf.minusSeconds(90), 510, "[]");

        assertThat(mine(asOf, userId)).singleElement().satisfies(p -> assertThat(p.focusing()).isFalse());
    }

    @Test
    void 자정을_걸치면_날짜별_조각으로_나누고_마지막_조각만_latest다() {
        Instant reported = at(16, 0, 30, 0);
        draft(userId, at(15, 23, 30, 0), reported, 3600, "[]");

        assertThat(mine(reported, userId))
                .extracting(LivePiece::statDate, LivePiece::focusSec, LivePiece::latest)
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 15), 1800, false), tuple(LocalDate.of(2026, 9, 16), 1800, true));
    }

    @Test
    void 늘린_시간이_구간_경계를_넘으면_새_구간에_들어간다() {
        // 06:43:00 시작, 06:59:40 보고(순공 1000초) → 07:00:20에 40초를 늘리면 오전에 20초
        draft(userId, at(15, 6, 43, 0), at(15, 6, 59, 40), 1000, "[]");

        assertThat(mine(at(15, 7, 0, 20), userId)).singleElement().satisfies(p -> assertThat(p.slots())
                .containsExactlyInAnyOrder(
                        new SessionSlot(TimeSlot.DAWN, LocalDate.of(2026, 9, 15), 1020),
                        new SessionSlot(TimeSlot.MORNING, LocalDate.of(2026, 9, 15), 20)));
    }

    @Test
    void 읽지_못하는_draft는_건너뛰고_나머지는_계산한다() {
        Instant asOf = at(15, 15, 0, 0);
        long broken = user();
        draft(broken, asOf.minusSeconds(600), asOf.minusSeconds(10), 590, "{\"bad\":1}");
        draft(userId, asOf.minusSeconds(600), asOf.minusSeconds(10), 590, "[]");

        assertThat(mine(asOf, broken)).isEmpty();
        assertThat(mine(asOf, userId)).hasSize(1);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionLivePiecesTest"`
Expected: 컴파일 실패 (`LivePiece`, `livePieces` 없음)

- [ ] **Step 3: 구현**

`src/main/java/project/study/studysession/dto/LivePiece.java`:

```java
package project.study.studysession.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.studysession.entity.SessionSlot;

/**
 * 진행 중 세션(draft)을 확정과 같은 규칙으로 나눈 조각 하나 (BY-828). latest는 draft의 마지막 조각(지금 시각이 속한 조각),
 * focusing은 draft가 지금 집중 중인지, achievedAt은 그 값의 기준 시각(집중 중이면 asOf, 아니면 마지막 수신)이다.
 */
public record LivePiece(
        long userId,
        LocalDate statDate,
        int focusSec,
        int studySec,
        List<SessionSlot> slots,
        boolean latest,
        boolean focusing,
        Instant achievedAt) {}
```

`ActiveStudySessionService.java` — 클래스에 `@Slf4j`(lombok.extern.slf4j.Slf4j)를 달고, import에 `io.sentry.Sentry`, `java.time.Duration`, `java.time.Instant`, `java.util.ArrayList`, `java.util.Comparator`, `project.study.studysession.dto.LivePiece`, `project.study.studysession.entity.StudySession`을 추가한 뒤(이미 있는 것은 건너뜀) 클래스 끝에 추가:

```java
    /** 집중 중 판정 — 마지막 수신이 이 안이어야 한다 (BY-828). 하트비트(30초)를 한 번 놓쳐도 집중 중으로 본다. */
    public static final Duration FOCUSING_WINDOW = Duration.ofSeconds(60);

    /**
     * 진행 중 세션을 랭킹 집계용 조각으로 나눈다 (BY-828, ADR-0028). 확정과 같은 분할(자정·시간대 구간)을 쓰고, 지금 집중 중이면
     * 마지막 수신 뒤 경과 시간(최대 60초)만큼 늘린 가상 스냅샷으로 계산해 asOf 시점 값을 갖게 한다. 하트비트가 끊긴 draft도
     * 자동 확정되면 같은 값이 되므로 포함한다. 읽지 못하는 draft는 건너뛴다 — 폐기는 확정 스케줄러 몫이다.
     */
    @Transactional(readOnly = true)
    public List<LivePiece> livePieces(Instant asOf) {
        List<LivePiece> pieces = new ArrayList<>();
        for (ActiveStudySession draft : activeStudySessionRepository.findAll()) {
            try {
                pieces.addAll(toLivePieces(draft, asOf));
            } catch (RuntimeException e) {
                log.warn("랭킹 집계에서 draft를 건너뜀: draftId={}", draft.getId(), e);
                Sentry.captureException(e);
            }
        }
        return pieces;
    }

    private List<LivePiece> toLivePieces(ActiveStudySession draft, Instant asOf) {
        List<StatusEvent> events =
                objectMapper.readValue(draft.getEvents(), new TypeReference<List<StatusEventRequest>>() {}).stream()
                        .map(StatusEventRequest::toEntity)
                        .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                        .toList();
        boolean focusing = isFocusing(draft, events, asOf);
        int extendSec = focusing
                ? (int) Math.max(0, Duration.between(draft.getLastSeenAt(), asOf).toSeconds())
                : 0;
        List<Instant> cuts = StudySessionSplitter.computeCuts(
                draft.getStartedAt(), draft.getReportedAt().plusSeconds(extendSec));
        List<StudySession> sessions = StudySessionSplitter.buildSessions(
                draft.getUserId(),
                cuts,
                StudySessionSplitter.computeSegmentWeights(cuts, events),
                draft.getStudySec() + extendSec,
                draft.getFocusSec() + extendSec,
                List.of(),
                List.of());
        Instant achievedAt = focusing ? asOf : draft.getLastSeenAt();
        List<LivePiece> pieces = new ArrayList<>(sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            StudySession piece = sessions.get(i);
            pieces.add(new LivePiece(
                    piece.getUserId(),
                    piece.getStatDate(),
                    piece.getFocusSec(),
                    piece.getStudySec(),
                    List.copyOf(piece.getSlots()),
                    i == sessions.size() - 1,
                    focusing,
                    achievedAt));
        }
        return pieces;
    }

    /** 마지막 수신이 60초 안이고 진행 중인 이벤트가 없으면 집중 중이다 — 앱은 진행 중 이벤트를 reportedAt에서 닫아 보낸다. */
    static boolean isFocusing(ActiveStudySession draft, List<StatusEvent> sortedEvents, Instant asOf) {
        if (draft.getLastSeenAt().isBefore(asOf.minus(FOCUSING_WINDOW))) {
            return false;
        }
        return sortedEvents.isEmpty() || !sortedEvents.getLast().getEndedAt().equals(draft.getReportedAt());
    }
```

`RankingSource.java` — 의존성 세 개와 메서드 세 개를 추가한다 (import `java.time.Instant`, `org.springframework.transaction.annotation.Transactional`, `project.study.studysession.StudySessionThresholds`, `project.study.studysession.dto.LivePiece`, `project.study.studysession.repository.StudySessionRepository`):

```java
    private final StudySessionRepository studySessionRepository;
    private final StudySessionService studySessionService;
    private final ActiveStudySessionService activeStudySessionService;

    public List<LivePiece> livePieces(Instant asOf) {
        return activeStudySessionService.livePieces(asOf);
    }

    /** 지금 이어지는 연속 공부일 — 스트릭 API의 streak와 같은 값. */
    public int currentStreak(long userId) {
        return studySessionService.streak(userId, null, null).streak();
    }

    /** from~to 중 순공 1분 이상 조각이 있는 날 수 — 누적 일수의 최근 페이스 계산용. */
    @Transactional(readOnly = true)
    public int studiedDays(long userId, LocalDate from, LocalDate to) {
        return studySessionRepository
                .findDistinctStatDatesBetween(userId, from, to, StudySessionThresholds.MIN_LIST_FOCUS_SEC)
                .size();
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionLivePiecesTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/studysession/dto/LivePiece.java src/main/java/project/study/studysession/service/ActiveStudySessionService.java src/main/java/project/study/studysession/service/RankingSource.java src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java
git commit -m "feat: 진행 중 세션을 랭킹 집계 조각으로 나눈다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 랭킹판 종류·조합 검증·기간 달력

**Files:**
- Create: `src/main/java/project/study/ranking/RankingPeriod.java`
- Create: `src/main/java/project/study/ranking/RankingBoardType.java`
- Create: `src/main/java/project/study/ranking/RankingBoard.java`
- Create: `src/main/java/project/study/ranking/RankingCalendar.java`
- Test: `src/test/java/project/study/ranking/RankingBoardTest.java`
- Test: `src/test/java/project/study/ranking/RankingCalendarTest.java`

**Interfaces:**
- Consumes: `TimeSlot`(Task 1), `BadRequestException`
- Produces:
  - `enum RankingPeriod { DAILY, WEEKLY, MONTHLY }`
  - `enum RankingBoardType { FOCUS_TIME, FOCUS_RATE, TIME_SLOT, TOTAL_TIME, TOTAL_DAYS, MAX_STREAK }` + `hallOfFame()`, `supports(RankingPeriod)`
  - `record RankingBoard(RankingBoardType type, RankingPeriod period, TimeSlot slot)` + `static of(type, period, slot, int offset)`(검증, 위반 시 `BadRequestException`), `key()` 예: `FOCUS_TIME:WEEKLY`, `TIME_SLOT:DAILY:NIGHT`, `TOTAL_TIME`
  - `RankingCalendar.window(RankingBoard, Instant now, int offset): RankingCalendar.Window`, `record Window(LocalDate start, LocalDate end, Instant closesAt)`, `RankingCalendar.ALL_TIME_START = 2000-01-01`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/RankingBoardTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import org.junit.jupiter.api.Test;
import project.study.common.exception.BadRequestException;
import project.study.studysession.entity.TimeSlot;

class RankingBoardTest {

    @Test
    void 기간_판은_지원하는_기간만_받는다() {
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_RATE, DAILY, null, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TIME_SLOT, MONTHLY, TimeSlot.MORNING, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, null, null, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 명예의_전당은_기간_구간_직전_기간을_받지_않는다() {
        assertThatThrownBy(() -> RankingBoard.of(TOTAL_TIME, WEEKLY, null, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TOTAL_TIME, null, TimeSlot.DAWN, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TOTAL_TIME, null, null, -1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 구간은_시간대_판에만_준다() {
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, WEEKLY, TimeSlot.MORNING, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(TIME_SLOT, DAILY, null, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void offset은_0과_마이너스1만_된다() {
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, WEEKLY, null, -2)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RankingBoard.of(FOCUS_TIME, WEEKLY, null, 1)).isInstanceOf(BadRequestException.class);
        assertThat(RankingBoard.of(FOCUS_TIME, WEEKLY, null, -1)).isEqualTo(new RankingBoard(FOCUS_TIME, WEEKLY, null));
    }

    @Test
    void 키는_종목_기간_구간을_잇는다() {
        assertThat(new RankingBoard(FOCUS_TIME, WEEKLY, null).key()).isEqualTo("FOCUS_TIME:WEEKLY");
        assertThat(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.NIGHT).key()).isEqualTo("TIME_SLOT:DAILY:NIGHT");
        assertThat(new RankingBoard(TOTAL_TIME, null, null).key()).isEqualTo("TOTAL_TIME");
    }
}
```

`src/test/java/project/study/ranking/RankingCalendarTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import project.study.ranking.RankingCalendar.Window;
import project.study.studysession.entity.TimeSlot;

class RankingCalendarTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-10은 토요일 — 그 주는 10/5(월)~10/11(일) */
    private static final Instant SAT_15 = at(10, 10, 15);

    private static Instant at(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, KST).toInstant();
    }

    private static LocalDate date(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void 일간은_그날이고_다음날_0시에_마감한다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, DAILY, null), SAT_15, 0))
                .isEqualTo(new Window(date(10, 10), date(10, 10), at(10, 11, 0)));
    }

    @Test
    void 주간은_월요일부터_일요일이고_다음_월요일_0시에_마감한다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, WEEKLY, null), SAT_15, 0))
                .isEqualTo(new Window(date(10, 5), date(10, 11), at(10, 12, 0)));
    }

    @Test
    void 월간은_1일부터_말일이다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, MONTHLY, null), SAT_15, 0))
                .isEqualTo(new Window(date(10, 1), date(10, 31), at(11, 1, 0)));
    }

    @Test
    void 직전_기간은_한_기간_앞이다() {
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, DAILY, null), SAT_15, -1))
                .isEqualTo(new Window(date(10, 9), date(10, 9), at(10, 10, 0)));
        assertThat(RankingCalendar.window(new RankingBoard(FOCUS_TIME, MONTHLY, null), SAT_15, -1))
                .isEqualTo(new Window(date(9, 1), date(9, 30), at(10, 1, 0)));
    }

    @Test
    void 심야_일간은_자정_뒤에도_전날_판이고_4시에_마감한다() {
        RankingBoard night = new RankingBoard(TIME_SLOT, DAILY, TimeSlot.NIGHT);

        assertThat(RankingCalendar.window(night, at(10, 11, 2), 0))
                .isEqualTo(new Window(date(10, 10), date(10, 10), at(10, 11, 4)));
        assertThat(RankingCalendar.window(night, at(10, 11, 5), 0))
                .isEqualTo(new Window(date(10, 11), date(10, 11), at(10, 12, 4)));
    }

    @Test
    void 심야_주간은_월요일_4시에_마감한다() {
        assertThat(RankingCalendar.window(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.NIGHT), at(10, 12, 2), 0))
                .isEqualTo(new Window(date(10, 5), date(10, 11), at(10, 12, 4)));
    }

    @Test
    void 명예의_전당은_처음부터_오늘까지이고_마감이_없다() {
        assertThat(RankingCalendar.window(new RankingBoard(TOTAL_TIME, null, null), SAT_15, 0))
                .isEqualTo(new Window(RankingCalendar.ALL_TIME_START, date(10, 10), null));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingBoardTest" --tests "project.study.ranking.RankingCalendarTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`src/main/java/project/study/ranking/RankingPeriod.java`:

```java
package project.study.ranking;

/** 랭킹판 기간 (BY-828) — KST 일간 00시·주간 월요일 00시·월간 1일 00시에 마감한다(심야판만 04시). */
public enum RankingPeriod {
    DAILY,
    WEEKLY,
    MONTHLY
}
```

`src/main/java/project/study/ranking/RankingBoardType.java`:

```java
package project.study.ranking;

import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.MONTHLY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.util.EnumSet;
import java.util.Set;

/** 랭킹 종목 (BY-828). 기간이 없는 셋(누적 시간·누적 일수·연속 공부 일수)이 명예의 전당이다. */
public enum RankingBoardType {
    FOCUS_TIME(EnumSet.of(DAILY, WEEKLY, MONTHLY)),
    FOCUS_RATE(EnumSet.of(WEEKLY, MONTHLY)),
    TIME_SLOT(EnumSet.of(DAILY, WEEKLY)),
    TOTAL_TIME(EnumSet.noneOf(RankingPeriod.class)),
    TOTAL_DAYS(EnumSet.noneOf(RankingPeriod.class)),
    MAX_STREAK(EnumSet.noneOf(RankingPeriod.class));

    private final Set<RankingPeriod> periods;

    RankingBoardType(Set<RankingPeriod> periods) {
        this.periods = periods;
    }

    /** 명예의 전당 — 리셋 없이 누적되고 진행 중 세션을 반영하지 않는다. */
    public boolean hallOfFame() {
        return periods.isEmpty();
    }

    public boolean supports(RankingPeriod period) {
        return periods.contains(period);
    }
}
```

`src/main/java/project/study/ranking/RankingBoard.java`:

```java
package project.study.ranking;

import java.util.StringJoiner;
import project.study.common.exception.BadRequestException;
import project.study.studysession.entity.TimeSlot;

/** 랭킹판 하나(종목·기간·시간대 구간) (BY-828). 없는 조합은 of에서 400으로 막는다. */
public record RankingBoard(RankingBoardType type, RankingPeriod period, TimeSlot slot) {

    /** offset은 0(지금 기간)·-1(직전 기간)만, 직전 기간은 기간 판에만 있다. 시간대 판의 slot은 호출자가 먼저 정한다. */
    public static RankingBoard of(RankingBoardType type, RankingPeriod period, TimeSlot slot, int offset) {
        if (offset != 0 && offset != -1) {
            throw new BadRequestException("offset은 0 또는 -1이어야 합니다");
        }
        if (type.hallOfFame()) {
            if (period != null || slot != null || offset != 0) {
                throw new BadRequestException("명예의 전당은 period·slot·offset을 받지 않습니다");
            }
            return new RankingBoard(type, null, null);
        }
        if (period == null || !type.supports(period)) {
            throw new BadRequestException(type + " 랭킹판에 " + period + " 기간은 없습니다");
        }
        if ((type == RankingBoardType.TIME_SLOT) != (slot != null)) {
            throw new BadRequestException("slot은 시간대 랭킹판에만, 반드시 줍니다");
        }
        return new RankingBoard(type, period, slot);
    }

    /** 캐시·마감 기록 키 — 예: FOCUS_TIME:WEEKLY, TIME_SLOT:DAILY:NIGHT, TOTAL_TIME */
    public String key() {
        StringJoiner key = new StringJoiner(":").add(type.name());
        if (period != null) {
            key.add(period.name());
        }
        if (slot != null) {
            key.add(slot.name());
        }
        return key.toString();
    }
}
```

`src/main/java/project/study/ranking/RankingCalendar.java`:

```java
package project.study.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import project.study.studysession.entity.TimeSlot;

/** 랭킹판의 기간 범위와 마감 시각 (BY-828). 모든 경계는 KST다. */
public final class RankingCalendar {

    /** 명예의 전당 집계 시작일 — 서비스 시작 전이라 모든 기록을 포함한다. */
    public static final LocalDate ALL_TIME_START = LocalDate.of(2000, 1, 1);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime NIGHT_CLOSE = LocalTime.of(4, 0);

    private RankingCalendar() {}

    /** 날짜 범위 [start, end]와 마감 시각. 명예의 전당은 처음부터 오늘까지이고 마감이 없다(null). */
    public record Window(LocalDate start, LocalDate end, Instant closesAt) {}

    /** offset -1은 직전 기간. 심야판은 (지금 − 4시간)의 날짜가 기준이고 마감이 4시간 늦다. */
    public static Window window(RankingBoard board, Instant now, int offset) {
        if (board.type().hallOfFame()) {
            return new Window(ALL_TIME_START, now.atZone(KST).toLocalDate(), null);
        }
        boolean night = board.slot() == TimeSlot.NIGHT;
        LocalDate reference = night ? TimeSlot.slotDateOf(now) : now.atZone(KST).toLocalDate();
        LocalDate start = shift(periodStart(board.period(), reference), board.period(), offset);
        LocalDate next = shift(start, board.period(), 1);
        LocalTime closeTime = night ? NIGHT_CLOSE : LocalTime.MIDNIGHT;
        return new Window(start, next.minusDays(1), next.atTime(closeTime).atZone(KST).toInstant());
    }

    private static LocalDate periodStart(RankingPeriod period, LocalDate date) {
        return switch (period) {
            case DAILY -> date;
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> date.withDayOfMonth(1);
        };
    }

    private static LocalDate shift(LocalDate start, RankingPeriod period, int periods) {
        return switch (period) {
            case DAILY -> start.plusDays(periods);
            case WEEKLY -> start.plusWeeks(periods);
            case MONTHLY -> start.plusMonths(periods);
        };
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingBoardTest" --tests "project.study.ranking.RankingCalendarTest"`
Expected: PASS (12 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/ranking/ src/test/java/project/study/ranking/
git commit -m "feat: 랭킹판 종류와 기간 달력을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 순위표와 배치 (순수 로직)

**Files:**
- Create: `src/main/java/project/study/ranking/engine/RankingEntry.java`
- Create: `src/main/java/project/study/ranking/engine/RankedEntry.java`
- Create: `src/main/java/project/study/ranking/engine/Standings.java`
- Create: `src/main/java/project/study/ranking/engine/Placement.java`
- Create: `src/main/java/project/study/ranking/engine/StreakGroup.java`
- Create: `src/main/java/project/study/ranking/engine/Tiers.java`
- Test: `src/test/java/project/study/ranking/engine/StandingsTest.java`
- Test: `src/test/java/project/study/ranking/engine/PlacementTest.java`
- Test: `src/test/java/project/study/ranking/engine/StreakGroupTest.java`
- Test: `src/test/java/project/study/ranking/engine/TiersTest.java`

**Interfaces:**
- Produces:
  - `record RankingEntry(long userId, String nickname, double value, Instant achievedAt, boolean focusing, long focusSec, long studySec)` + `ORDER`, `of(userId, nickname, value, achievedAt)`, `advancedTo(Instant from, Instant to)`, `achievedDate(): LocalDate`
  - `record RankedEntry(int rank, RankingEntry entry)`
  - `record Standings(List<RankingEntry> entries, Instant asOf)` + `of(Collection, Instant)`, `advancedTo(Instant now)`, `place(RankingEntry meOrNull): Placement`, `rankOf(RankingEntry hypothetical): int`
  - `record Placement(List<RankingEntry> merged, int myIndex)` + `WINDOW = 5`, `present()`, `size()`, `me()`, `myRank()`, `podium()`, `around()`, `windowAround(int index)` → `List<RankedEntry>`, `above()`, `below()`, `topPercent()`, `static windowStart(int size, int center)`
  - `record StreakGroup(int days, int startIndex, int count)` + `contains(int)`, `static of(List<RankingEntry>)`, `static indexContaining(List<StreakGroup>, int)`
  - `Tiers.PERCENTS = [1, 5, 10, 20, 30, 50]`, `Tiers.cutoffRank(int percent, int size)`, `Tiers.next(int myPercent, int size): OptionalInt`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/engine/StandingsTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandingsTest {

    private static final Instant T0 = Instant.parse("2026-10-10T06:00:00Z");

    private static RankingEntry e(long id, double value) {
        return RankingEntry.of(id, "u" + id, value, T0);
    }

    private static RankingEntry e(long id, double value, Instant achievedAt) {
        return RankingEntry.of(id, "u" + id, value, achievedAt);
    }

    @Test
    void 값이_크면_앞이고_같으면_먼저_도달한_사람_그다음_userId가_앞이다() {
        Standings s = Standings.of(List.of(e(3, 100, T0.plusSeconds(5)), e(4, 100, T0), e(1, 200), e(2, 100, T0)), T0);

        assertThat(s.entries()).extracting(RankingEntry::userId).containsExactly(1L, 2L, 4L, 3L);
    }

    @Test
    void 나를_끼우면_순위표에_남은_내_옛_줄은_새_값으로_대신한다() {
        Placement p = Standings.of(List.of(e(1, 300), e(2, 200), e(3, 100)), T0).place(e(3, 250));

        assertThat(p.merged()).extracting(RankingEntry::userId).containsExactly(1L, 3L, 2L);
        assertThat(p.myRank()).isEqualTo(2);
        assertThat(p.me().value()).isEqualTo(250);
    }

    @Test
    void 내가_없으면_남들만_있고_내_순위는_없다() {
        Placement p = Standings.of(List.of(e(1, 300), e(2, 200)), T0).place(null);

        assertThat(p.present()).isFalse();
        assertThat(p.size()).isEqualTo(2);
        assertThat(p.me()).isNull();
        assertThat(p.around()).isEmpty();
    }

    @Test
    void 집중_중인_줄은_요청_시각까지_올라가_순위가_바뀔_수_있다() {
        RankingEntry focusing = new RankingEntry(1, "u1", 100, T0, true, 0, 0);

        Standings s = Standings.of(List.of(focusing, e(2, 105)), T0).advancedTo(T0.plusSeconds(10));

        assertThat(s.entries()).extracting(RankingEntry::userId).containsExactly(1L, 2L);
        assertThat(s.entries().getFirst().value()).isEqualTo(110);
        assertThat(s.asOf()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void 가정한_값이_받을_순위를_낸다() {
        Standings s = Standings.of(List.of(e(1, 300), e(2, 200), e(3, 100)), T0);

        assertThat(s.rankOf(e(9, 150))).isEqualTo(3);
        assertThat(s.rankOf(e(2, 400))).isEqualTo(1);
    }
}
```

`src/test/java/project/study/ranking/engine/PlacementTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlacementTest {

    private static final Instant T0 = Instant.parse("2026-10-10T06:00:00Z");

    /** size명, 값은 내림차순, myIndex 자리가 나다(-1이면 나 없음) */
    private static Placement placement(int size, int myIndex) {
        List<RankingEntry> entries = IntStream.range(0, size)
                .mapToObj(i -> RankingEntry.of(i + 1, "u" + (i + 1), 100_000 - i, T0))
                .toList();
        return new Placement(entries, myIndex);
    }

    private static List<Integer> ranks(List<RankedEntry> rows) {
        return rows.stream().map(RankedEntry::rank).toList();
    }

    @Test
    void 가운데면_앞_2_나_뒤_2다() {
        assertThat(ranks(placement(10, 5).around())).containsExactly(4, 5, 6, 7, 8);
    }

    @Test
    void 일위면_나와_뒤_4명이고_바로_위가_없다() {
        Placement p = placement(10, 0);

        assertThat(ranks(p.around())).containsExactly(1, 2, 3, 4, 5);
        assertThat(p.above()).isNull();
        assertThat(p.below().userId()).isEqualTo(2);
    }

    @Test
    void 이위면_앞_1명과_뒤_3명이다() {
        assertThat(ranks(placement(10, 1).around())).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void 꼴찌면_앞_4명과_나고_바로_아래가_없다() {
        Placement p = placement(10, 9);

        assertThat(ranks(p.around())).containsExactly(6, 7, 8, 9, 10);
        assertThat(p.below()).isNull();
    }

    @Test
    void 참가자가_다섯보다_적으면_전부다() {
        Placement p = placement(3, 1);

        assertThat(ranks(p.around())).containsExactly(1, 2, 3);
        assertThat(ranks(p.podium())).containsExactly(1, 2, 3);
    }

    @Test
    void 혼자면_앞뒤가_없고_상위_100퍼센트다() {
        Placement p = placement(1, 0);

        assertThat(p.above()).isNull();
        assertThat(p.below()).isNull();
        assertThat(p.topPercent()).isEqualTo(100);
    }

    @Test
    void 상위_퍼센트는_올림이고_최소_1이다() {
        assertThat(placement(50, 0).topPercent()).isEqualTo(2);
        assertThat(placement(300, 0).topPercent()).isEqualTo(1);
        assertThat(placement(3257, 1204).topPercent()).isEqualTo(37);
    }

    @Test
    void 내가_없어도_주어진_자리_주변_다섯_줄을_낸다() {
        assertThat(ranks(placement(10, -1).windowAround(9))).containsExactly(6, 7, 8, 9, 10);
        assertThat(ranks(placement(2, -1).windowAround(2))).containsExactly(1, 2);
    }
}
```

`src/test/java/project/study/ranking/engine/StreakGroupTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StreakGroupTest {

    private static final Instant T0 = Instant.parse("2026-10-10T06:00:00Z");

    private static List<RankingEntry> days(int... values) {
        return IntStream.range(0, values.length)
                .mapToObj(i -> RankingEntry.of(i + 1, "u" + (i + 1), values[i], T0))
                .toList();
    }

    @Test
    void 같은_일수끼리_묶는다() {
        assertThat(StreakGroup.of(days(5, 5, 4, 4, 4, 3)))
                .containsExactly(new StreakGroup(5, 0, 2), new StreakGroup(4, 2, 3), new StreakGroup(3, 5, 1));
    }

    @Test
    void 자리가_속한_묶음을_찾는다() {
        List<StreakGroup> groups = StreakGroup.of(days(5, 5, 4, 4, 4, 3));

        assertThat(StreakGroup.indexContaining(groups, 3)).isEqualTo(1);
        assertThat(StreakGroup.indexContaining(groups, 5)).isEqualTo(2);
    }

    @Test
    void 빈_순위표는_묶음이_없다() {
        assertThat(StreakGroup.of(List.of())).isEmpty();
    }
}
```

`src/test/java/project/study/ranking/engine/TiersTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TiersTest {

    @Test
    void 다음_구간은_내_상위_퍼센트보다_좁은_가장_가까운_구간이다() {
        assertThat(Tiers.next(7, 100)).hasValue(5);
        assertThat(Tiers.next(37, 100)).hasValue(30);
        assertThat(Tiers.next(55, 100)).hasValue(50);
    }

    @Test
    void 일퍼센트_안이면_다음_구간이_없다() {
        assertThat(Tiers.next(1, 100)).isEmpty();
    }

    @Test
    void 참가자가_적어_아무도_못_드는_구간은_건너뛴다() {
        assertThat(Tiers.next(15, 20)).hasValue(10);
        assertThat(Tiers.next(15, 9)).isEmpty();
    }

    @Test
    void 컷_순위는_내림이다() {
        assertThat(Tiers.cutoffRank(10, 25)).isEqualTo(2);
        assertThat(Tiers.cutoffRank(1, 99)).isZero();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.ranking.engine.*"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`src/main/java/project/study/ranking/engine/RankingEntry.java`:

```java
package project.study.ranking.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

/**
 * 순위표 한 줄 (BY-828). value는 정렬 값 — 시간 판은 초, 일수 판은 일, 집중률은 % 원값. focusSec·studySec는 집중률·순공 판에서만
 * 채운다(따라잡기 계산용). nickname은 가정한 줄(예상 순위 계산)이면 null일 수 있다.
 */
public record RankingEntry(
        long userId, String nickname, double value, Instant achievedAt, boolean focusing, long focusSec, long studySec) {

    /** 값 내림차순 → 먼저 도달한 사람 → userId (공동 순위 없음). */
    public static final Comparator<RankingEntry> ORDER = Comparator.comparingDouble(RankingEntry::value)
            .reversed()
            .thenComparing(RankingEntry::achievedAt)
            .thenComparingLong(RankingEntry::userId);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static RankingEntry of(long userId, String nickname, double value, Instant achievedAt) {
        return new RankingEntry(userId, nickname, value, achievedAt, false, 0, 0);
    }

    /** 집중 중이면 from부터 to까지 흐른 초만큼 값을 올린다 — 캐시된 값을 요청 시각으로 맞춘다. */
    public RankingEntry advancedTo(Instant from, Instant to) {
        long seconds = Duration.between(from, to).toSeconds();
        if (!focusing || seconds <= 0) {
            return this;
        }
        return new RankingEntry(userId, nickname, value + seconds, to, true, focusSec + seconds, studySec + seconds);
    }

    /** 도달 시각의 KST 날짜 — 조각 종료는 반개구간의 끝이라 자정에 끝난 조각은 그 전날의 기록이다. */
    public LocalDate achievedDate() {
        return achievedAt.minusNanos(1).atZone(KST).toLocalDate();
    }
}
```

`src/main/java/project/study/ranking/engine/RankedEntry.java`:

```java
package project.study.ranking.engine;

/** 순위(1부터)가 붙은 줄. */
public record RankedEntry(int rank, RankingEntry entry) {}
```

`src/main/java/project/study/ranking/engine/Standings.java`:

```java
package project.study.ranking.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** 한 판·한 기간의 정렬된 참가자 목록 (BY-828) — 불변. asOf는 값의 기준 시각이다. */
public record Standings(List<RankingEntry> entries, Instant asOf) {

    public Standings {
        entries = List.copyOf(entries);
    }

    public static Standings of(Collection<RankingEntry> entries, Instant asOf) {
        List<RankingEntry> sorted = new ArrayList<>(entries);
        sorted.sort(RankingEntry.ORDER);
        return new Standings(sorted, asOf);
    }

    /** 집중 중인 줄을 now 값으로 올려 다시 정렬한다. 집중 중인 줄이 없으면 기준 시각만 바꾼다. */
    public Standings advancedTo(Instant now) {
        if (entries.stream().noneMatch(RankingEntry::focusing)) {
            return new Standings(entries, now);
        }
        return of(entries.stream().map(entry -> entry.advancedTo(asOf, now)).toList(), now);
    }

    /** me를 끼운 배치 — 순위표에 남아 있는 내 옛 줄은 빼고 새로 읽은 me로 대신한다. me가 null이면 남들만이다. */
    public Placement place(RankingEntry me) {
        List<RankingEntry> merged = new ArrayList<>(entries.size() + 1);
        for (RankingEntry entry : entries) {
            if (me == null || entry.userId() != me.userId()) {
                merged.add(entry);
            }
        }
        if (me == null) {
            return new Placement(Collections.unmodifiableList(merged), -1);
        }
        int index = Collections.binarySearch(merged, me, RankingEntry.ORDER);
        int insertion = index >= 0 ? index : -index - 1;
        merged.add(insertion, me);
        return new Placement(Collections.unmodifiableList(merged), insertion);
    }

    /** 가정한 줄이 들어가면 받을 순위(1부터) — 같은 userId의 줄은 빼고 센다. */
    public int rankOf(RankingEntry hypothetical) {
        return place(hypothetical).myRank();
    }
}
```

`src/main/java/project/study/ranking/engine/Placement.java`:

```java
package project.study.ranking.engine;

import java.util.ArrayList;
import java.util.List;

/** 순위표에 나를 끼운 결과 (BY-828). myIndex가 -1이면 나는 참가자가 아니다. merged는 읽기 전용이다. */
public record Placement(List<RankingEntry> merged, int myIndex) {

    /** 내 주변 리스트 줄 수 — 앞 2 · 나 · 뒤 2. */
    public static final int WINDOW = 5;

    public boolean present() {
        return myIndex >= 0;
    }

    public int size() {
        return merged.size();
    }

    public RankingEntry me() {
        return present() ? merged.get(myIndex) : null;
    }

    public int myRank() {
        return myIndex + 1;
    }

    public List<RankedEntry> podium() {
        return ranked(0, Math.min(3, size()));
    }

    public List<RankedEntry> around() {
        return present() ? windowAround(myIndex) : List.of();
    }

    /** index를 가운데 둔 다섯 줄 — 내가 없을 때 예상 자리 주변을 보여줄 때도 쓴다. */
    public List<RankedEntry> windowAround(int index) {
        int start = windowStart(size(), index);
        return ranked(start, Math.min(size(), start + WINDOW));
    }

    public RankingEntry above() {
        return myIndex > 0 ? merged.get(myIndex - 1) : null;
    }

    public RankingEntry below() {
        return present() && myIndex < size() - 1 ? merged.get(myIndex + 1) : null;
    }

    /** max(1, ceil(순위 × 100 ÷ 참가자 수)) — 정수로 올림한다. */
    public int topPercent() {
        return Math.max(1, (myRank() * 100 + size() - 1) / size());
    }

    /** size칸 목록에서 center를 가운데 두는 다섯 칸 창의 시작 — 앞이 모자라면 뒤를, 뒤가 모자라면 앞을 더 채운다. */
    public static int windowStart(int size, int center) {
        int start = Math.max(0, center - 2);
        int end = Math.min(size, start + WINDOW);
        return Math.max(0, end - WINDOW);
    }

    private List<RankedEntry> ranked(int from, int to) {
        List<RankedEntry> rows = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            rows.add(new RankedEntry(i + 1, merged.get(i)));
        }
        return rows;
    }
}
```

`src/main/java/project/study/ranking/engine/StreakGroup.java`:

```java
package project.study.ranking.engine;

import java.util.ArrayList;
import java.util.List;

/** 연속 공부 일수 리스트의 한 줄 — 같은 일수의 연속 구간 [startIndex, startIndex + count) (BY-828). */
public record StreakGroup(int days, int startIndex, int count) {

    public boolean contains(int index) {
        return index >= startIndex && index < startIndex + count;
    }

    /** 일수가 같은 연속 줄을 묶는다 — 순위표가 정렬돼 있어 같은 값은 붙어 있다. */
    public static List<StreakGroup> of(List<RankingEntry> sorted) {
        List<StreakGroup> groups = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= sorted.size(); i++) {
            if (i == sorted.size() || sorted.get(i).value() != sorted.get(start).value()) {
                groups.add(new StreakGroup((int) sorted.get(start).value(), start, i - start));
                start = i;
            }
        }
        return groups;
    }

    public static int indexContaining(List<StreakGroup> groups, int index) {
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g).contains(index)) {
                return g;
            }
        }
        return -1;
    }
}
```

`src/main/java/project/study/ranking/engine/Tiers.java`:

```java
package project.study.ranking.engine;

import java.util.List;
import java.util.OptionalInt;

/** 상위 % 목표 구간 (BY-828) — "상위 5%까지 28시간", "3시간이면 상위 50%"에 쓴다. */
public final class Tiers {

    public static final List<Integer> PERCENTS = List.of(1, 5, 10, 20, 30, 50);

    private Tiers() {}

    /** 상위 percent% 안의 마지막 순위 — floor(size × percent ÷ 100). 0이면 그 구간엔 아무도 없다. */
    public static int cutoffRank(int percent, int size) {
        return size * percent / 100;
    }

    /** 내 상위 %보다 좁은 구간 중 가장 가까운 것 — 지금 참가자 수로 닿을 수 있는(컷 순위 ≥ 1) 구간만. */
    public static OptionalInt next(int myPercent, int size) {
        for (int i = PERCENTS.size() - 1; i >= 0; i--) {
            int percent = PERCENTS.get(i);
            if (percent < myPercent && cutoffRank(percent, size) >= 1) {
                return OptionalInt.of(percent);
            }
        }
        return OptionalInt.empty();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.engine.*"`
Expected: PASS (20 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/ranking/engine/ src/test/java/project/study/ranking/engine/
git commit -m "feat: 랭킹 순위표와 내 자리 배치를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 순위표 계산기·캐시·공급자

**Files:**
- Create: `src/main/java/project/study/ranking/engine/LiveSnapshot.java`
- Create: `src/main/java/project/study/ranking/engine/RateTotals.java`
- Create: `src/main/java/project/study/ranking/engine/BoardView.java`
- Create: `src/main/java/project/study/ranking/engine/StandingsCalculator.java`
- Create: `src/main/java/project/study/ranking/engine/StandingsCache.java`
- Create: `src/main/java/project/study/ranking/engine/StandingsProvider.java`
- Test: `src/test/java/project/study/ranking/RankingIntegrationTestBase.java`
- Test: `src/test/java/project/study/ranking/engine/StandingsCalculatorTest.java`
- Test: `src/test/java/project/study/ranking/engine/StandingsCacheTest.java`

**Interfaces:**
- Consumes: `RankingSource`(Task 4·5), `RankingBoard`·`RankingCalendar.Window`(Task 6), `Standings`·`RankingEntry`·`Placement`(Task 7)
- Produces:
  - `record LiveSnapshot(List<LivePiece> pieces, Instant asOf)`, `record RateTotals(long focusSec, long studySec)` + `rate()`, `record BoardView(Standings standings, Placement placement, LiveSnapshot live)`
  - `StandingsCalculator.compute(RankingBoard, Window, Instant asOf, List<LivePiece> live, Long onlyUserId): List<RankingEntry>`(정렬 안 됨), `rateTotals(RankingBoard, Window, Instant, List<LivePiece>, long userId): Optional<RateTotals>`, `static requiredRateFocusSec(RankingPeriod): long`
  - `StandingsCache.get(Object key, Duration ttl, Supplier<T> loader): T`, `evictExpired()`, `clear()`
  - `StandingsProvider.view(RankingBoard, Window, long userId, Instant now): BoardView`, `standings(RankingBoard, Window, LiveSnapshot, Instant now): Standings`, `live(): LiveSnapshot`, `CURRENT_TTL = 10초`, `SLOW_TTL = 60초`
  - 테스트 기반 `RankingIntegrationTestBase` — 전용 컨텍스트(전용 컨테이너), 시계 `NOW = 2026-10-10 15:00 KST`, 헬퍼 `user`, `withdraw`, `session`, `draft`, `kst`

- [ ] **Step 1: 테스트 기반과 실패하는 테스트 작성**

`src/test/java/project/study/ranking/RankingIntegrationTestBase.java`:

```java
package project.study.ranking;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.ranking.engine.StandingsCache;
import project.study.room.support.MutableClock;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.StudySessionService;

/**
 * 랭킹 통합테스트 공통 기반 (BY-828). 랭킹은 전체 사용자를 집계하므로 다른 테스트의 데이터가 섞이면 순위가 흔들린다 —
 * 이 클래스의 시계 설정이 컨텍스트 키를 갈라 랭킹 테스트만 쓰는 컨테이너가 뜨고, 매 테스트 전에 그 데이터를 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class RankingIntegrationTestBase {

    protected static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-10(토) 15:00 KST — 주간은 10/5(월)~10/11(일), 지금 구간은 오후다. */
    protected static final Instant NOW = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, KST).toInstant();

    @TestConfiguration
    static class RankingClockConfig {

        @Bean
        @Primary
        MutableClock rankingClock() {
            return MutableClock.at(NOW);
        }
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StandingsCache standingsCache;

    @Autowired
    protected StudySessionService studySessionService;

    @Autowired
    protected ActiveStudySessionRepository activeStudySessionRepository;

    @BeforeEach
    void resetRankingState() {
        jdbc.execute("TRUNCATE users, active_study_session RESTART IDENTITY CASCADE");
        standingsCache.clear();
        clock.set(NOW);
    }

    protected static Instant kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, KST).toInstant();
    }

    protected long user(String nickname) {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname);
    }

    protected void withdraw(long userId) {
        jdbc.update("UPDATE users SET status = 'DELETE' WHERE id = ?", userId);
    }

    /** 확정 세션 — 실제 제출 경로(자정 분할·구간 행)를 그대로 탄다. 총공부는 길이 전체, 순공은 focusSec. */
    protected void session(long userId, Instant start, int minutes, int focusSec) {
        Instant end = start.plusSeconds(minutes * 60L);
        studySessionService.create(
                userId, new StudySessionCreateRequest(start, end, minutes * 60, focusSec, List.of(), null, null), false);
    }

    /** 진행 중 스냅샷 — lastSeenAt = reportedAt, 총공부는 길이 전체. events는 JSON 배열 문자열 */
    protected void draft(long userId, Instant start, Instant reportedAt, int focusSec, String events) {
        int length = (int) Duration.between(start, reportedAt).toSeconds();
        activeStudySessionRepository.upsertSnapshot(userId, start, reportedAt, reportedAt, length, focusSec, events);
    }
}
```

`@AutoConfigureMockMvc`의 패키지는 Boot 4에서 `org.springframework.boot.webmvc.test.autoconfigure`다 — 다른 API 테스트(`StudyDaysBoundaryApiTest`)의 import를 그대로 따른다.

`src/test/java/project/study/ranking/engine/StandingsCalculatorTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

class StandingsCalculatorTest extends RankingIntegrationTestBase {

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private RankingSource source;

    private List<RankingEntry> compute(RankingBoard board, Long only) {
        List<RankingEntry> entries = calculator.compute(
                board, RankingCalendar.window(board, NOW, 0), NOW, source.livePieces(NOW), only);
        return Standings.of(entries, NOW).entries();
    }

    @Test
    void 순공은_확정_세션에_진행_중_조각을_더한다() {
        long a = user("a");
        session(a, kst(10, 6, 9, 0), 60, 3000);
        draft(a, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        long b = user("b");
        session(b, kst(10, 7, 9, 0), 60, 3300);

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), null))
                .extracting(RankingEntry::nickname, RankingEntry::value, RankingEntry::focusing)
                .containsExactly(tuple("a", 3600.0, true), tuple("b", 3300.0, false));
    }

    @Test
    void 일분_미만_조각과_탈퇴자는_빠지고_진행_중만_있는_사람도_들어간다() {
        long tiny = user("tiny");
        session(tiny, kst(10, 6, 9, 0), 1, 59);
        long gone = user("gone");
        session(gone, kst(10, 6, 9, 0), 60, 3000);
        withdraw(gone);
        long goneLive = user("goneLive");
        draft(goneLive, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");
        withdraw(goneLive);
        long live = user("live");
        draft(live, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), null))
                .extracting(RankingEntry::nickname)
                .containsExactly("live");
    }

    @Test
    void 집중률은_참가_조건을_넘은_사람만_넣는다() {
        long in = user("in");
        session(in, kst(10, 6, 0, 30), 720, 39_600);
        long out = user("out");
        session(out, kst(10, 7, 0, 30), 540, 32_400);

        assertThat(compute(new RankingBoard(FOCUS_RATE, WEEKLY, null), null)).singleElement().satisfies(e -> {
            assertThat(e.nickname()).isEqualTo("in");
            assertThat(e.value()).isCloseTo(91.67, within(0.01));
            assertThat(e.focusSec()).isEqualTo(39_600);
            assertThat(e.studySec()).isEqualTo(43_200);
        });
    }

    @Test
    void 시간대는_그_구간_순공만_더하고_지금_그_구간에서_집중_중인_사람만_집중_중이다() {
        long early = user("early");
        session(early, kst(10, 10, 6, 30), 60, 3600);
        long now = user("now");
        draft(now, NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(compute(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.MORNING), null))
                .extracting(RankingEntry::nickname, RankingEntry::value)
                .containsExactly(tuple("early", 1800.0));
        assertThat(compute(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.AFTERNOON), null))
                .extracting(RankingEntry::nickname, RankingEntry::value, RankingEntry::focusing)
                .containsExactly(tuple("now", 600.0, true));
    }

    @Test
    void 한_사용자만_계산할_수_있다() {
        long a = user("a");
        session(a, kst(10, 6, 9, 0), 60, 3000);
        long b = user("b");
        session(b, kst(10, 7, 9, 0), 60, 3300);

        assertThat(compute(new RankingBoard(FOCUS_TIME, WEEKLY, null), a))
                .extracting(RankingEntry::userId)
                .containsExactly(a);
    }

    @Test
    void 명예의_전당_연속_일수는_최장_연속이다() {
        long k = user("k");
        for (int day : new int[] {1, 2, 3, 5, 6}) {
            session(k, kst(10, day, 9, 0), 15, 900);
        }

        assertThat(compute(new RankingBoard(MAX_STREAK, null, null), null))
                .extracting(RankingEntry::value)
                .containsExactly(3.0);
    }
}
```

`src/test/java/project/study/ranking/engine/StandingsCacheTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import project.study.room.support.MutableClock;

class StandingsCacheTest {

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-10T06:00:00Z"));
    private final StandingsCache cache = new StandingsCache(clock);
    private final AtomicInteger loads = new AtomicInteger();

    private int get() {
        return cache.get("k", Duration.ofSeconds(10), loads::incrementAndGet);
    }

    @Test
    void 수명_안에서는_다시_계산하지_않고_지나면_다시_계산한다() {
        assertThat(get()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(9));
        assertThat(get()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(get()).isEqualTo(2);
    }

    @Test
    void 만료된_지_10분_넘은_키는_지운다() {
        get();
        clock.advance(Duration.ofMinutes(11));
        cache.evictExpired();
        clock.set(Instant.parse("2026-10-10T06:00:05Z"));

        assertThat(get()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.ranking.engine.StandingsCalculatorTest" --tests "project.study.ranking.engine.StandingsCacheTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`src/main/java/project/study/ranking/engine/LiveSnapshot.java`:

```java
package project.study.ranking.engine;

import java.time.Instant;
import java.util.List;
import project.study.studysession.dto.LivePiece;

/** 한 시각(asOf)에 나눈 진행 중 조각 전체 — 판들이 10초 동안 공유한다. */
public record LiveSnapshot(List<LivePiece> pieces, Instant asOf) {}
```

`src/main/java/project/study/ranking/engine/RateTotals.java`:

```java
package project.study.ranking.engine;

/** 집중률 판의 한 사용자 합계 — 참가 조건과 상관없이(미달 화면용). */
public record RateTotals(long focusSec, long studySec) {

    /** 순공 ÷ 총공부 × 100 (원값). */
    public double rate() {
        return studySec == 0 ? 0 : focusSec * 100.0 / studySec;
    }
}
```

`src/main/java/project/study/ranking/engine/BoardView.java`:

```java
package project.study.ranking.engine;

/** 요청 시각으로 올린 순위표, 내 줄을 끼운 배치, 그 계산에 쓴 진행 중 조각. */
public record BoardView(Standings standings, Placement placement, LiveSnapshot live) {}
```

`src/main/java/project/study/ranking/engine/StandingsCalculator.java`:

```java
package project.study.ranking.engine;

import static project.study.studysession.StudySessionThresholds.MIN_LIST_FOCUS_SEC;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/**
 * 판 하나의 줄 목록을 만든다 (BY-828, ADR-0028). 순공·집중률·시간대는 확정 합계에 진행 중 조각을 더하고, 명예의 전당은 확정
 * 세션만 본다. 결과는 정렬하지 않는다 — Standings.of가 정렬한다.
 */
@Component
@RequiredArgsConstructor
public class StandingsCalculator {

    /** 집중률 참가 조건 — 주간 순공 10시간, 월간 30시간 (명세 §1-1). */
    static final long WEEKLY_RATE_MIN_FOCUS_SEC = 36_000;

    static final long MONTHLY_RATE_MIN_FOCUS_SEC = 108_000;

    private final RankingSource source;

    /** 판 전체(onlyUserId=null) 또는 한 사용자의 줄. live는 asOf에 나눈 진행 중 조각이다. */
    public List<RankingEntry> compute(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, Long onlyUserId) {
        return switch (board.type()) {
            case FOCUS_TIME, TIME_SLOT -> accumulate(board, window, asOf, live, onlyUserId).entrySet().stream()
                    .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().focusSec))
                    .toList();
            case FOCUS_RATE -> accumulate(board, window, asOf, live, onlyUserId).entrySet().stream()
                    .filter(e -> e.getValue().focusSec >= requiredRateFocusSec(board.period()))
                    .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().rate()))
                    .toList();
            case TOTAL_TIME -> source.periodTotals(RankingCalendar.ALL_TIME_START, window.end(), onlyUserId).stream()
                    .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.focusSec(), r.achievedAt()))
                    .toList();
            case TOTAL_DAYS -> source.studyDays(window.end(), onlyUserId).stream()
                    .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                    .toList();
            case MAX_STREAK -> source.maxStreaks(window.end(), onlyUserId).stream()
                    .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                    .toList();
        };
    }

    /** 집중률 판의 한 사용자 합계 — 참가 조건과 상관없이. 기간에 1분 이상 조각이 없으면 empty. */
    public Optional<RateTotals> rateTotals(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, long userId) {
        return Optional.ofNullable(accumulate(board, window, asOf, live, userId).get(userId))
                .map(t -> new RateTotals(t.focusSec, t.studySec));
    }

    public static long requiredRateFocusSec(RankingPeriod period) {
        return period == RankingPeriod.MONTHLY ? MONTHLY_RATE_MIN_FOCUS_SEC : WEEKLY_RATE_MIN_FOCUS_SEC;
    }

    private Map<Long, Totals> accumulate(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, Long onlyUserId) {
        Map<Long, Totals> totals = new HashMap<>();
        for (RankingTotalRow row : finalizedRows(board, window, onlyUserId)) {
            totals.put(row.userId(), new Totals(row.nickname(), row.focusSec(), row.studySec(), row.achievedAt()));
        }
        for (LivePiece piece : live) {
            boolean included = (onlyUserId == null || piece.userId() == onlyUserId)
                    && piece.focusSec() >= MIN_LIST_FOCUS_SEC;
            Contribution contribution = included ? contribution(board, window, asOf, piece) : null;
            if (contribution != null) {
                totals.computeIfAbsent(piece.userId(), id -> new Totals(null, 0, 0, Instant.EPOCH))
                        .add(contribution);
            }
        }
        fillLiveOnlyNicknames(totals);
        return totals;
    }

    private List<RankingTotalRow> finalizedRows(RankingBoard board, Window window, Long onlyUserId) {
        return board.type() == RankingBoardType.TIME_SLOT
                ? source.slotTotals(board.slot(), window.start(), window.end(), onlyUserId)
                : source.periodTotals(window.start(), window.end(), onlyUserId);
    }

    /** 조각이 이 판에 더하는 몫 — 판 기간 밖이면 null. 집중 중 표시는 지금 이 판에 값이 오르는 경우에만 켠다. */
    private static Contribution contribution(RankingBoard board, Window window, Instant asOf, LivePiece piece) {
        if (board.type() == RankingBoardType.TIME_SLOT) {
            long focus = piece.slots().stream()
                    .filter(slot -> slot.getSlot() == board.slot() && within(window, slot.getSlotDate()))
                    .mapToLong(SessionSlot::getFocusSec)
                    .sum();
            boolean focusing = piece.latest()
                    && piece.focusing()
                    && TimeSlot.at(asOf) == board.slot()
                    && within(window, TimeSlot.slotDateOf(asOf));
            return focus == 0 ? null : new Contribution(focus, 0, piece.achievedAt(), focusing);
        }
        if (!within(window, piece.statDate())) {
            return null;
        }
        boolean focusing = board.type() == RankingBoardType.FOCUS_TIME && piece.latest() && piece.focusing();
        return new Contribution(piece.focusSec(), piece.studySec(), piece.achievedAt(), focusing);
    }

    /** 진행 중 조각만 있는 사용자는 확정 집계 줄이 없어 닉네임을 따로 읽는다 — 탈퇴자는 여기서 빠진다. */
    private void fillLiveOnlyNicknames(Map<Long, Totals> totals) {
        List<Long> missing = totals.entrySet().stream()
                .filter(e -> e.getValue().nickname == null)
                .map(Map.Entry::getKey)
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<Long, String> nicknames = source.activeNicknames(missing);
        for (Long userId : missing) {
            String nickname = nicknames.get(userId);
            if (nickname == null) {
                totals.remove(userId);
            } else {
                totals.get(userId).nickname = nickname;
            }
        }
    }

    private static boolean within(Window window, LocalDate date) {
        return !date.isBefore(window.start()) && !date.isAfter(window.end());
    }

    private record Contribution(long focusSec, long studySec, Instant achievedAt, boolean focusing) {}

    private static final class Totals {

        private String nickname;
        private long focusSec;
        private long studySec;
        private Instant achievedAt;
        private boolean focusing;

        private Totals(String nickname, long focusSec, long studySec, Instant achievedAt) {
            this.nickname = nickname;
            this.focusSec = focusSec;
            this.studySec = studySec;
            this.achievedAt = achievedAt;
        }

        private void add(Contribution contribution) {
            focusSec += contribution.focusSec();
            studySec += contribution.studySec();
            if (contribution.achievedAt().isAfter(achievedAt)) {
                achievedAt = contribution.achievedAt();
            }
            focusing |= contribution.focusing();
        }

        private double rate() {
            return studySec == 0 ? 0 : focusSec * 100.0 / studySec;
        }

        private RankingEntry toEntry(long userId, double value) {
            return new RankingEntry(userId, nickname, value, achievedAt, focusing, focusSec, studySec);
        }
    }
}
```

`src/main/java/project/study/ranking/engine/StandingsCache.java`:

```java
package project.study.ranking.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 랭킹 순위표 메모리 캐시 (BY-828, ADR-0028) — 키마다 수명이 있고, 같은 키 동시 요청은 먼저 온 계산을 기다렸다 그 결과를 쓴다.
 * 태스크 메모리라 태스크가 여럿이면 각자 계산한다.
 */
@Component
public class StandingsCache {

    private final Clock clock;
    private final ConcurrentHashMap<Object, Slot> slots = new ConcurrentHashMap<>();

    public StandingsCache(Clock clock) {
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Duration ttl, Supplier<T> loader) {
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            Instant now = clock.instant();
            if (slot.value == null || !now.isBefore(slot.expiresAt)) {
                slot.value = loader.get();
                slot.expiresAt = now.plus(ttl);
            }
            return (T) slot.value;
        }
    }

    /** 만료된 지 10분 넘은 키를 지운다 — 지난 기간 키가 쌓이지 않게. */
    @Scheduled(fixedDelay = 600_000)
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(10));
        slots.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(cutoff));
    }

    /** 시계를 고정한 테스트가 서로의 캐시를 보지 않게 비운다. */
    public void clear() {
        slots.clear();
    }

    private static final class Slot {

        private Object value;
        private volatile Instant expiresAt = Instant.MIN;
    }
}
```

`src/main/java/project/study/ranking/engine/StandingsProvider.java`:

```java
package project.study.ranking.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar.Window;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.service.RankingSource;

/**
 * 캐시된 순위표와 새로 읽은 내 줄을 묶는다 (BY-828, ADR-0028). 순위표는 판·기간 단위로 캐시하고, 요청 시각까지 집중 중인 줄을
 * 올린 뒤, 캐시와 따로 매번 계산한 내 줄로 순위표의 내 옛 줄을 대신한다.
 */
@Component
@RequiredArgsConstructor
public class StandingsProvider {

    /** 진행 중 기간 판과 진행 중 조각 — FE 폴링(15초)보다 짧게. */
    public static final Duration CURRENT_TTL = Duration.ofSeconds(10);

    /** 명예의 전당·지난 기간 — 실시간이 아니다. */
    public static final Duration SLOW_TTL = Duration.ofSeconds(60);

    private static final String LIVE_KEY = "live";

    private final StandingsCalculator calculator;
    private final StandingsCache cache;
    private final RankingSource source;
    private final Clock clock;

    public BoardView view(RankingBoard board, Window window, long userId, Instant now) {
        LiveSnapshot live = live();
        Standings standings = standings(board, window, live, now).advancedTo(now);
        RankingEntry me = calculator.compute(board, window, live.asOf(), piecesFor(board, live), userId).stream()
                .findFirst()
                .map(entry -> entry.advancedTo(live.asOf(), now))
                .orElse(null);
        return new BoardView(standings, standings.place(me), live);
    }

    public Standings standings(RankingBoard board, Window window, LiveSnapshot live, Instant now) {
        boolean slow = board.type().hallOfFame() || !window.closesAt().isAfter(now);
        return cache.get(
                new StandingsKey(board.key(), window.start()),
                slow ? SLOW_TTL : CURRENT_TTL,
                () -> Standings.of(
                        calculator.compute(board, window, live.asOf(), piecesFor(board, live), null), live.asOf()));
    }

    public LiveSnapshot live() {
        return cache.get(LIVE_KEY, CURRENT_TTL, () -> {
            Instant at = clock.instant();
            return new LiveSnapshot(source.livePieces(at), at);
        });
    }

    private static List<LivePiece> piecesFor(RankingBoard board, LiveSnapshot live) {
        return board.type().hallOfFame() ? List.of() : live.pieces();
    }

    private record StandingsKey(String board, LocalDate periodStart) {}
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.engine.*"`
Expected: PASS (Task 7 포함 28 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/ranking/engine/ src/test/java/project/study/ranking/
git commit -m "feat: 랭킹 순위표를 계산하고 판 단위로 캐시한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: 랭킹판 응답 조립

**Files:**
- Create: `src/main/java/project/study/ranking/dto/RankingBoardResponse.java`
- Create: `src/main/java/project/study/ranking/service/BoardValues.java`
- Create: `src/main/java/project/study/ranking/service/BoardExtras.java`
- Create: `src/main/java/project/study/ranking/service/RankingBoardService.java`
- Test: `src/test/java/project/study/ranking/service/RankingBoardServiceTest.java`
- Test: `src/test/java/project/study/ranking/service/RankingBoardExtrasTest.java`

**Interfaces:**
- Consumes: `StandingsProvider.view/standings/live`, `StandingsCalculator.rateTotals/requiredRateFocusSec`, `RankingSource.maxStreaks/currentStreak/periodTotals/studiedDays`, `Placement`, `StreakGroup`, `Tiers`
- Produces:
  - `RankingBoardResponse(type, period, slot, periodStart, closesAt, asOf, podium, me, around, above, below, startNowRank, eligibility, nextTier, streak, aroundGroups, goalExamples)`와 중첩 record `BoardEntry(rank, nickname, value, focusing, me)`, `BoardMe(rank, value, focusing, topPercent)`, `BoardNeighbor(nickname, gap, focusing, catchUpFocusSec)`, `RateEligibility(eligible, focusSec, requiredFocusSec, focusRate, expectedRank)`, `NextTier(percent, remaining, etaDays)`, `StreakCard(maxDays, maxStart, maxEnd, currentDays, currentIsBest, nextRankIfContinue)`, `StreakGroupRow(days, rank, nickname, othersCount, achievedDate, me, myOrder)`, `GoalExample(percent, value)`
  - `RankingBoardService.board(long userId, RankingBoardType type, RankingPeriod period, TimeSlot slot, int offset): RankingBoardResponse`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/service/RankingBoardServiceTest.java`:

```java
package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.dto.RankingBoardResponse.BoardMe;
import project.study.ranking.dto.RankingBoardResponse.BoardNeighbor;
import project.study.ranking.dto.RankingBoardResponse.GoalExample;

/** 순공 판으로 공통 응답(시상대·내 순위·앞뒤·실시간·직전 기간)을 확인한다 (BY-828). */
class RankingBoardServiceTest extends RankingIntegrationTestBase {

    @Autowired
    private RankingBoardService service;

    /** 이번 주 화요일(10/6) 09:00에 분 단위 순공 세션 하나 */
    private long weekly(String nickname, int focusMinutes) {
        long id = user(nickname);
        session(id, kst(10, 6, 9, 0), focusMinutes, focusMinutes * 60);
        return id;
    }

    private RankingBoardResponse weeklyBoard(long userId) {
        return service.board(userId, FOCUS_TIME, WEEKLY, null, 0);
    }

    @Test
    void 순공_주간은_시상대_내_순위_앞뒤_두_명을_준다() {
        long[] ids = new long[7];
        for (int i = 0; i < 7; i++) {
            ids[i] = weekly("u" + (i + 1), 70 - i * 10);
        }

        RankingBoardResponse r = weeklyBoard(ids[3]);

        assertThat(r.periodStart()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(r.closesAt()).isEqualTo(kst(10, 12, 0, 0));
        assertThat(r.asOf()).isEqualTo(NOW);
        assertThat(r.podium()).extracting(BoardEntry::nickname).containsExactly("u1", "u2", "u3");
        assertThat(r.me()).isEqualTo(new BoardMe(4, 2400L, false, 58));
        assertThat(r.around()).extracting(BoardEntry::rank).containsExactly(2, 3, 4, 5, 6);
        assertThat(r.around()).filteredOn(BoardEntry::me).extracting(BoardEntry::nickname).containsExactly("u4");
        assertThat(r.above()).isEqualTo(new BoardNeighbor("u3", 600L, false, null));
        assertThat(r.below()).isEqualTo(new BoardNeighbor("u5", 600L, false, null));
        assertThat(r.startNowRank()).isNull();
        assertThat(r.goalExamples()).isNull();
    }

    @Test
    void 일위면_뒤로_네_명이고_바로_위가_없다() {
        long first = weekly("u1", 70);
        for (int i = 2; i <= 6; i++) {
            weekly("u" + i, 70 - (i - 1) * 10);
        }

        RankingBoardResponse r = weeklyBoard(first);

        assertThat(r.around()).extracting(BoardEntry::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(r.above()).isNull();
        assertThat(r.below()).isEqualTo(new BoardNeighbor("u2", 600L, false, null));
    }

    @Test
    void 이번_주_기록이_없으면_지금_시작할_때_순위와_지난주_분포로_만든_목표를_준다() {
        for (int i = 1; i <= 10; i++) {
            session(user("last" + i), kst(10, 1, 9, 0), i * 10, i * 600);
        }
        for (int i = 1; i <= 3; i++) {
            weekly("now" + i, 30);
        }
        long me = user("me");

        RankingBoardResponse r = weeklyBoard(me);

        assertThat(r.me()).isNull();
        assertThat(r.around()).isEmpty();
        assertThat(r.startNowRank()).isEqualTo(4);
        assertThat(r.goalExamples())
                .containsExactly(
                        new GoalExample(10, 6060), new GoalExample(20, 5460), new GoalExample(30, 4860), new GoalExample(50, 3660));
    }

    @Test
    void 집중_중인_사람은_요청_시각까지_값이_오르고_추월하면_순위가_바뀐다() {
        long me = weekly("me", 60);
        session(user("b"), kst(10, 6, 12, 0), 11, 610);
        draft(user("a"), NOW.minusSeconds(600), NOW.minusSeconds(10), 590, "[]");

        assertThat(weeklyBoard(me).podium())
                .extracting(BoardEntry::nickname, BoardEntry::value, BoardEntry::focusing)
                .containsExactly(tuple("me", 3600L, false), tuple("b", 610L, false), tuple("a", 600L, true));

        clock.advance(Duration.ofSeconds(5)); // 캐시(10초) 안 — 캐시된 값을 요청 시각으로 올린다
        RankingBoardResponse later = weeklyBoard(me);
        assertThat(later.asOf()).isEqualTo(NOW.plusSeconds(5));
        assertThat(later.podium().get(2).value()).isEqualTo(605L);

        clock.advance(Duration.ofSeconds(15)); // 마지막 수신 뒤 30초 — 620초로 b(610)를 넘는다
        assertThat(weeklyBoard(me).podium()).extracting(BoardEntry::nickname).containsExactly("me", "a", "b");
    }

    @Test
    void 세션을_막_끝낸_내_값은_캐시와_상관없이_바로_반영되고_내가_두_번_나오지_않는다() {
        weekly("other", 50);
        long me = weekly("me", 40);
        assertThat(weeklyBoard(me).me().rank()).isEqualTo(2);

        session(me, kst(10, 10, 14, 0), 20, 1200);
        RankingBoardResponse r = weeklyBoard(me);

        assertThat(r.me()).isEqualTo(new BoardMe(1, 3600L, false, 50));
        assertThat(r.podium()).extracting(BoardEntry::nickname).containsExactly("me", "other");
    }

    @Test
    void 직전_기간을_조회할_수_있다() {
        long me = user("me");
        session(me, kst(10, 9, 9, 0), 30, 1800);

        RankingBoardResponse r = service.board(me, FOCUS_TIME, DAILY, null, -1);

        assertThat(r.periodStart()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(r.closesAt()).isEqualTo(kst(10, 10, 0, 0));
        assertThat(r.me().value()).isEqualTo(1800L);
    }
}
```

`src/test/java/project/study/ranking/service/RankingBoardExtrasTest.java`:

```java
package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.dto.RankingBoardResponse.NextTier;
import project.study.ranking.dto.RankingBoardResponse.RateEligibility;
import project.study.ranking.dto.RankingBoardResponse.StreakCard;
import project.study.ranking.dto.RankingBoardResponse.StreakGroupRow;
import project.study.studysession.entity.TimeSlot;

/** 판별 추가 필드 — 집중률·시간대·누적 시간·연속 공부 일수 (BY-828). */
class RankingBoardExtrasTest extends RankingIntegrationTestBase {

    @Autowired
    private RankingBoardService service;

    private void streak(long userId, LocalDate first, int days) {
        for (int i = 0; i < days; i++) {
            session(userId, first.plusDays(i).atTime(9, 0).atZone(KST).toInstant(), 15, 900);
        }
    }

    @Test
    void 집중률_참가_조건_미달이면_진행도와_예상_순위와_그_자리_주변을_준다() {
        session(user("x"), kst(10, 6, 0, 30), 720, 39_600);
        session(user("y"), kst(10, 7, 0, 30), 720, 43_200);
        long me = user("me");
        session(me, kst(10, 8, 0, 30), 600, 32_400);

        RankingBoardResponse r = service.board(me, FOCUS_RATE, WEEKLY, null, 0);

        assertThat(r.me()).isNull();
        assertThat(r.eligibility()).isEqualTo(new RateEligibility(false, 32_400, 36_000, 90.0, 3));
        assertThat(r.around()).extracting(BoardEntry::nickname).containsExactly("y", "x");
        assertThat(r.startNowRank()).isNull();
    }

    @Test
    void 집중률은_바로_위를_넘는_데_필요한_순공을_준다() {
        session(user("x"), kst(10, 6, 0, 30), 720, 39_600);
        long me = user("me");
        session(me, kst(10, 8, 0, 30), 720, 38_000);

        RankingBoardResponse r = service.board(me, FOCUS_RATE, WEEKLY, null, 0);

        assertThat(r.me().rank()).isEqualTo(2);
        assertThat(r.above().catchUpFocusSec()).isEqualTo(1601L);
        assertThat(r.above().gap()).isEqualTo(3.7);
        assertThat(r.eligibility().eligible()).isTrue();
    }

    @Test
    void 시간대_구간을_안_주면_지금_구간이다() {
        long me = user("me");
        session(me, kst(10, 10, 12, 30), 60, 3600);

        RankingBoardResponse r = service.board(me, TIME_SLOT, DAILY, null, 0);

        assertThat(r.slot()).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(r.me().value()).isEqualTo(3600L);
        assertThat(r.closesAt()).isEqualTo(kst(10, 11, 0, 0));
    }

    @Test
    void 누적_시간은_다음_구간까지_남은_양과_지금_페이스로_걸리는_날을_준다() {
        long me = 0;
        for (int hours = 1; hours <= 20; hours++) {
            long id = user("u" + hours);
            session(id, kst(10, 9, 0, 0), hours * 60, hours * 3600);
            if (hours == 18) {
                me = id;
            }
        }

        RankingBoardResponse r = service.board(me, TOTAL_TIME, null, null, 0);

        assertThat(r.me().rank()).isEqualTo(3);
        assertThat(r.me().topPercent()).isEqualTo(15);
        assertThat(r.nextTier()).isEqualTo(new NextTier(10, 3601, 1));
        assertThat(r.periodStart()).isNull();
        assertThat(r.closesAt()).isNull();
    }

    @Test
    void 연속_공부_일수는_같은_일수끼리_묶고_내_묶음엔_몇_번째로_달성했는지_준다() {
        streak(user("a"), LocalDate.of(2026, 9, 1), 5);
        streak(user("b"), LocalDate.of(2026, 9, 2), 5);
        streak(user("c"), LocalDate.of(2026, 9, 10), 4);
        long me = user("me");
        streak(me, LocalDate.of(2026, 9, 20), 4);
        streak(user("d"), LocalDate.of(2026, 9, 25), 3);

        RankingBoardResponse r = service.board(me, MAX_STREAK, null, null, 0);

        assertThat(r.me().rank()).isEqualTo(4);
        assertThat(r.around()).isEmpty();
        assertThat(r.aroundGroups())
                .containsExactly(
                        new StreakGroupRow(5, 1, "a", 1, LocalDate.of(2026, 9, 5), false, null),
                        new StreakGroupRow(4, 4, "me", 1, LocalDate.of(2026, 9, 23), true, 2),
                        new StreakGroupRow(3, 5, "d", 0, LocalDate.of(2026, 9, 27), false, null));
        assertThat(r.streak())
                .isEqualTo(new StreakCard(4, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23), 0, false, null));
    }

    @Test
    void 지금_연속이_최고_기록이면_내일도_하면_받을_순위를_준다() {
        streak(user("p"), LocalDate.of(2026, 9, 1), 5);
        streak(user("q"), LocalDate.of(2026, 9, 10), 4);
        long me = user("me");
        streak(me, LocalDate.of(2026, 10, 7), 3); // 10/7~10/9, 오늘(10/10)은 아직

        RankingBoardResponse r = service.board(me, MAX_STREAK, null, null, 0);

        assertThat(r.streak())
                .isEqualTo(new StreakCard(3, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 9), 3, true, 3));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.ranking.service.*"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`src/main/java/project/study/ranking/dto/RankingBoardResponse.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/**
 * 랭킹판 조회 응답 (BY-828). 값·차이 단위는 시간 판 초, 일수 판 일, 집중률 %p(소수 1자리). 다른 사용자의 userId는 싣지 않는다 —
 * 행 키는 고유한 nickname이다. 판에 해당하지 않는 추가 필드는 null이다.
 */
@Schema(description = "랭킹판 조회 응답")
public record RankingBoardResponse(
        RankingBoardType type,
        RankingPeriod period,
        TimeSlot slot,
        @Schema(description = "기간 시작일(KST) — 명예의 전당은 null") LocalDate periodStart,
        @Schema(description = "마감 시각 — 명예의 전당은 null") Instant closesAt,
        @Schema(description = "값 기준 시각 — focusing인 값은 이 시각부터 1초씩 올려 보간한다") Instant asOf,
        @Schema(description = "1·2·3위 (참가자가 적으면 그만큼)") List<BoardEntry> podium,
        @Schema(description = "내 순위 — 참가하지 않았으면 null") BoardMe me,
        @Schema(description = "앞 2 · 나 · 뒤 2 — 앞이 모자라면 뒤를 더. 연속 일수 판은 []") List<BoardEntry> around,
        @Schema(description = "바로 위 — 1위면 null") BoardNeighbor above,
        @Schema(description = "바로 아래 — 꼴찌면 null") BoardNeighbor below,
        @Schema(description = "me가 null일 때만: 지금 시작하면 받을 순위(참가자 + 1)") Integer startNowRank,
        @Schema(description = "집중률 판만: 참가 조건 진행도") RateEligibility eligibility,
        @Schema(description = "누적 시간·누적 일수 판만: 다음 상위 % 구간까지") NextTier nextTier,
        @Schema(description = "연속 공부 일수 판만: 최고 기록과 지금 연속") StreakCard streak,
        @Schema(description = "연속 공부 일수 판만: 같은 일수 묶음 앞 2 · 내 묶음 · 뒤 2") List<StreakGroupRow> aroundGroups,
        @Schema(description = "순공 주간 판에 me가 null일 때만: 지난주 분포의 구간별 필요 순공") List<GoalExample> goalExamples) {

    public record BoardEntry(int rank, String nickname, Number value, boolean focusing, boolean me) {}

    public record BoardMe(int rank, Number value, boolean focusing, int topPercent) {}

    public record BoardNeighbor(String nickname, Number gap, boolean focusing, Long catchUpFocusSec) {}

    public record RateEligibility(
            boolean eligible, long focusSec, long requiredFocusSec, double focusRate, Integer expectedRank) {}

    public record NextTier(int percent, long remaining, Integer etaDays) {}

    public record StreakCard(
            int maxDays,
            LocalDate maxStart,
            LocalDate maxEnd,
            int currentDays,
            boolean currentIsBest,
            Integer nextRankIfContinue) {}

    public record StreakGroupRow(
            int days, int rank, String nickname, int othersCount, LocalDate achievedDate, boolean me, Integer myOrder) {}

    public record GoalExample(int percent, long value) {}
}
```

`src/main/java/project/study/ranking/service/BoardValues.java`:

```java
package project.study.ranking.service;

import project.study.ranking.RankingBoardType;

/** 응답 값 표기 — 집중률은 % 소수 1자리, 나머지는 정수(초·일). */
final class BoardValues {

    private BoardValues() {}

    static Number value(RankingBoardType type, double raw) {
        if (type == RankingBoardType.FOCUS_RATE) {
            return round1(raw);
        }
        return Math.round(raw);
    }

    static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
```

`src/main/java/project/study/ranking/service/BoardExtras.java`:

```java
package project.study.ranking.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse.GoalExample;
import project.study.ranking.dto.RankingBoardResponse.NextTier;
import project.study.ranking.dto.RankingBoardResponse.RateEligibility;
import project.study.ranking.dto.RankingBoardResponse.StreakCard;
import project.study.ranking.dto.RankingBoardResponse.StreakGroupRow;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.RateTotals;
import project.study.ranking.engine.Standings;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.engine.StandingsProvider;
import project.study.ranking.engine.StreakGroup;
import project.study.ranking.engine.Tiers;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.service.RankingSource;

/** 판별 추가 필드 (BY-828) — 해당하지 않는 판이면 null을 준다. */
@Component
@RequiredArgsConstructor
class BoardExtras {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StandingsCalculator calculator;
    private final StandingsProvider provider;
    private final RankingSource source;

    /** 집중률 참가 진행도 — 기간에 세션이 없으면 null, 미달이면 지금 집중률로 참가할 때의 예상 순위를 붙인다. */
    RateEligibility eligibility(RankingBoard board, Window window, BoardView view, long userId, Instant now) {
        if (board.type() != RankingBoardType.FOCUS_RATE) {
            return null;
        }
        return calculator
                .rateTotals(board, window, view.live().asOf(), view.live().pieces(), userId)
                .map(totals -> toEligibility(board.period(), view.standings(), totals, userId, now))
                .orElse(null);
    }

    /** 누적 시간·누적 일수의 다음 상위 % 구간까지 남은 양 — 이미 1% 안이거나 닿을 구간이 없으면 null. */
    NextTier nextTier(RankingBoard board, Placement placement, long userId, Instant now) {
        RankingBoardType type = board.type();
        boolean cumulative = type == RankingBoardType.TOTAL_TIME || type == RankingBoardType.TOTAL_DAYS;
        if (!cumulative || !placement.present()) {
            return null;
        }
        OptionalInt tier = Tiers.next(placement.topPercent(), placement.size());
        if (tier.isEmpty()) {
            return null;
        }
        RankingEntry cutoff = placement.merged().get(Tiers.cutoffRank(tier.getAsInt(), placement.size()) - 1);
        long remaining = (long) (cutoff.value() - placement.me().value()) + 1;
        return new NextTier(tier.getAsInt(), remaining, etaDays(type, userId, remaining, today(now)));
    }

    /** 연속 공부 일수 카드 — 최고 기록과 지금 연속. 지금이 최고 기록이면 내일도 하면 받을 순위를 붙인다. */
    StreakCard streakCard(RankingBoard board, Standings standings, long userId, Instant now) {
        if (board.type() != RankingBoardType.MAX_STREAK) {
            return null;
        }
        return source.maxStreaks(today(now), userId).stream()
                .findFirst()
                .map(row -> {
                    int current = source.currentStreak(userId);
                    boolean currentIsBest = current > 0 && current == row.days();
                    Integer nextRank = currentIsBest
                            ? standings.rankOf(
                                    RankingEntry.of(userId, null, row.days() + 1, now.plus(Duration.ofDays(1))))
                            : null;
                    return new StreakCard(
                            row.days(), row.startDate(), row.endDate(), current, currentIsBest, nextRank);
                })
                .orElse(null);
    }

    /** 같은 일수 묶음 앞 2 · 내 묶음 · 뒤 2 — 내 묶음은 내 순위·이름과 같은 일수 중 몇 번째인지. */
    List<StreakGroupRow> streakGroups(RankingBoard board, Placement placement) {
        if (board.type() != RankingBoardType.MAX_STREAK) {
            return null;
        }
        if (!placement.present()) {
            return List.of();
        }
        List<StreakGroup> groups = StreakGroup.of(placement.merged());
        int start = Placement.windowStart(groups.size(), StreakGroup.indexContaining(groups, placement.myIndex()));
        return groups.subList(start, Math.min(groups.size(), start + Placement.WINDOW)).stream()
                .map(group -> toRow(group, placement))
                .toList();
    }

    /** 순공 주간에 이번 주 기록이 없을 때 — 지난주 최종 분포에서 구간마다 필요한 순공(분 단위 올림). */
    List<GoalExample> goalExamples(RankingBoard board, int offset, Placement placement, Instant now) {
        if (board.type() != RankingBoardType.FOCUS_TIME
                || board.period() != RankingPeriod.WEEKLY
                || offset != 0
                || placement.present()) {
            return null;
        }
        Window lastWeek = RankingCalendar.window(board, now, -1);
        List<RankingEntry> last =
                provider.standings(board, lastWeek, provider.live(), now).entries();
        List<GoalExample> examples = new ArrayList<>();
        for (int percent : Tiers.PERCENTS) {
            int cutoff = Tiers.cutoffRank(percent, last.size());
            if (cutoff >= 1) {
                examples.add(new GoalExample(percent, ceilToMinute((long) last.get(cutoff - 1).value() + 1)));
            }
        }
        return examples;
    }

    private static RateEligibility toEligibility(
            RankingPeriod period, Standings standings, RateTotals totals, long userId, Instant now) {
        long required = StandingsCalculator.requiredRateFocusSec(period);
        boolean eligible = totals.focusSec() >= required;
        Integer expectedRank = eligible
                ? null
                : standings.rankOf(new RankingEntry(
                        userId, null, totals.rate(), now, false, totals.focusSec(), totals.studySec()));
        return new RateEligibility(
                eligible, totals.focusSec(), required, BoardValues.round1(totals.rate()), expectedRank);
    }

    private Integer etaDays(RankingBoardType type, long userId, long remaining, LocalDate today) {
        LocalDate from = today.minusDays(6);
        double perDay = (type == RankingBoardType.TOTAL_TIME
                        ? source.periodTotals(from, today, userId).stream()
                                .mapToLong(RankingTotalRow::focusSec)
                                .sum()
                        : source.studiedDays(userId, from, today))
                / 7.0;
        return perDay <= 0 ? null : (int) Math.ceil(remaining / perDay);
    }

    private static StreakGroupRow toRow(StreakGroup group, Placement placement) {
        if (!group.contains(placement.myIndex())) {
            RankingEntry head = placement.merged().get(group.startIndex());
            return new StreakGroupRow(
                    group.days(), group.startIndex() + 1, head.nickname(), group.count() - 1, head.achievedDate(), false, null);
        }
        RankingEntry me = placement.me();
        return new StreakGroupRow(
                group.days(),
                placement.myRank(),
                me.nickname(),
                group.count() - 1,
                me.achievedDate(),
                true,
                placement.myIndex() - group.startIndex() + 1);
    }

    private static LocalDate today(Instant now) {
        return now.atZone(KST).toLocalDate();
    }

    private static long ceilToMinute(long seconds) {
        return (seconds + 59) / 60 * 60;
    }
}
```

`src/main/java/project/study/ranking/service/RankingBoardService.java`:

```java
package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.dto.RankingBoardResponse.BoardEntry;
import project.study.ranking.dto.RankingBoardResponse.BoardMe;
import project.study.ranking.dto.RankingBoardResponse.BoardNeighbor;
import project.study.ranking.dto.RankingBoardResponse.RateEligibility;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.StandingsProvider;
import project.study.studysession.entity.TimeSlot;

/** 랭킹판 조회 (BY-828, ADR-0028) — 캐시된 순위표에 새로 읽은 내 줄을 끼워 응답을 조립한다. */
@Service
@RequiredArgsConstructor
public class RankingBoardService {

    private final StandingsProvider provider;
    private final BoardExtras extras;
    private final Clock clock;

    public RankingBoardResponse board(
            long userId, RankingBoardType type, RankingPeriod period, TimeSlot slot, int offset) {
        Instant now = clock.instant();
        RankingBoard board = RankingBoard.of(type, period, resolveSlot(type, slot, now), offset);
        Window window = RankingCalendar.window(board, now, offset);
        BoardView view = provider.view(board, window, userId, now);
        Placement placement = view.placement();
        RankingEntry me = placement.me();
        RateEligibility eligibility = extras.eligibility(board, window, view, userId, now);
        return new RankingBoardResponse(
                type,
                board.period(),
                board.slot(),
                periodStart(board, window),
                window.closesAt(),
                now,
                entries(placement.podium(), userId, type),
                myRank(placement, type),
                around(placement, eligibility, userId, type),
                neighbor(placement.above(), me, type, true),
                neighbor(placement.below(), me, type, false),
                startNowRank(placement, type),
                eligibility,
                extras.nextTier(board, placement, userId, now),
                extras.streakCard(board, view.standings(), userId, now),
                extras.streakGroups(board, placement),
                extras.goalExamples(board, offset, placement, now));
    }

    /** 총공부 시간이 같을 때 바로 위 집중률을 넘으려면 더 해야 하는 순공(초) — 정수 비교로 부동소수 오차를 피한다. */
    static long catchUpFocusSec(RankingEntry above, RankingEntry me) {
        long needed = Math.floorDiv(above.focusSec() * me.studySec(), above.studySec()) + 1;
        return Math.max(1, needed - me.focusSec());
    }

    private static TimeSlot resolveSlot(RankingBoardType type, TimeSlot slot, Instant now) {
        return type == RankingBoardType.TIME_SLOT && slot == null ? TimeSlot.at(now) : slot;
    }

    private static LocalDate periodStart(RankingBoard board, Window window) {
        return board.type().hallOfFame() ? null : window.start();
    }

    private static BoardMe myRank(Placement placement, RankingBoardType type) {
        if (!placement.present()) {
            return null;
        }
        RankingEntry me = placement.me();
        return new BoardMe(
                placement.myRank(), BoardValues.value(type, me.value()), me.focusing(), placement.topPercent());
    }

    private static List<BoardEntry> around(
            Placement placement, RateEligibility eligibility, long userId, RankingBoardType type) {
        if (type == RankingBoardType.MAX_STREAK) {
            return List.of();
        }
        if (eligibility != null && !eligibility.eligible()) {
            return entries(placement.windowAround(eligibility.expectedRank() - 1), userId, type);
        }
        return entries(placement.around(), userId, type);
    }

    private static Integer startNowRank(Placement placement, RankingBoardType type) {
        return placement.present() || type == RankingBoardType.FOCUS_RATE ? null : placement.size() + 1;
    }

    private static List<BoardEntry> entries(List<RankedEntry> ranked, long userId, RankingBoardType type) {
        return ranked.stream()
                .map(row -> new BoardEntry(
                        row.rank(),
                        row.entry().nickname(),
                        BoardValues.value(type, row.entry().value()),
                        row.entry().focusing(),
                        row.entry().userId() == userId))
                .toList();
    }

    private static BoardNeighbor neighbor(RankingEntry other, RankingEntry me, RankingBoardType type, boolean above) {
        if (other == null || me == null) {
            return null;
        }
        Long catchUp = above && type == RankingBoardType.FOCUS_RATE ? catchUpFocusSec(other, me) : null;
        return new BoardNeighbor(
                other.nickname(), BoardValues.value(type, Math.abs(other.value() - me.value())), other.focusing(), catchUp);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.service.*"`
Expected: PASS (12 tests)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/ranking/dto/ src/main/java/project/study/ranking/service/ src/test/java/project/study/ranking/service/
git commit -m "feat: 랭킹판 응답을 판별 추가 필드와 함께 조립한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 랭킹판 조회 API

**Files:**
- Create: `src/main/java/project/study/ranking/controller/RankingController.java`
- Test: `src/test/java/project/study/ranking/RankingBoardApiTest.java`

**Interfaces:**
- Consumes: `RankingBoardService.board`
- Produces: `GET /api/rankings/board?type=&period=&slot=&offset=` (`API-Version: 1`, 토큰 필요)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/RankingBoardApiTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

class RankingBoardApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    private MockMvcRequestBuilder request(String query, long userId) {
        // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
        return mvc.get()
                .uri("/api/rankings/board?" + query)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    @Test
    void 랭킹판을_조회하고_다른_사람의_userId는_싣지_않는다() {
        long me = user("me");
        session(me, kst(10, 6, 9, 0), 60, 3000);
        session(user("other"), kst(10, 7, 9, 0), 60, 3300);

        assertThat(request("type=FOCUS_TIME&period=WEEKLY", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.periodStart", v -> assertThat(v).isEqualTo("2026-10-05"))
                .hasPathSatisfying("$.me.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.above.gap", v -> assertThat(v).isEqualTo(300))
                .hasPathSatisfying("$.podium[0].nickname", v -> assertThat(v).isEqualTo("other"))
                .doesNotHavePath("$.podium[0].userId");
    }

    @Test
    void 참가자가_없으면_시상대가_비고_지금_시작하면_1위다() {
        long me = user("me");

        assertThat(request("type=FOCUS_TIME&period=DAILY", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.startNowRank", v -> assertThat(v).isEqualTo(1))
                .extractingPath("$.podium")
                .asArray()
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "type=FOCUS_RATE&period=DAILY",
                "type=TIME_SLOT&period=MONTHLY",
                "type=FOCUS_TIME",
                "type=TOTAL_TIME&period=WEEKLY",
                "type=FOCUS_TIME&period=WEEKLY&slot=MORNING",
                "type=FOCUS_TIME&period=WEEKLY&offset=-2",
                "type=TOTAL_TIME&offset=-1",
                "type=NOPE"
            })
    void 없는_조합은_400이다(String query) {
        assertThat(request(query, user("me"))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.get()
                        .uri("/api/rankings/board?type=TOTAL_TIME")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingBoardApiTest"`
Expected: FAIL — 404 (엔드포인트 없음)

- [ ] **Step 3: 구현**

`src/main/java/project/study/ranking/controller/RankingController.java`:

```java
package project.study.ranking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingBoardResponse;
import project.study.ranking.service.RankingBoardService;
import project.study.studysession.entity.TimeSlot;

@Tag(name = "Ranking", description = "랭킹 API — 랭킹판(순공·집중률·시간대)과 명예의 전당을 조회한다 (BY-828)")
@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {

    private final RankingBoardService rankingBoardService;

    @Operation(summary = "랭킹판 조회", description = """
                    랭킹판 하나의 시상대(1~3위), 내 순위, 내 주변(앞 2 · 나 · 뒤 2), 바로 위·아래와의 차이를 준다. \
                    전체 참가 인원과 다른 사용자의 userId는 내려주지 않는다 — 행 키는 고유한 nickname이다.

                    - 종목·기간: 순공(FOCUS_TIME) 일·주·월, 집중률(FOCUS_RATE) 주·월(참가 조건 주 10시간·월 30시간), \
                    시간대(TIME_SLOT) 일·주(slot 생략 시 지금 구간), 명예의 전당(TOTAL_TIME·TOTAL_DAYS·MAX_STREAK, period 없음)
                    - 기간은 KST — 일간 00시, 주간 월요일 00시, 월간 1일 00시 마감. 심야(NIGHT)판만 04시 마감이고 시작한 날 기준이다
                    - 값·차이 단위: 시간 판 초, 일수 판 일, 집중률 %p(소수 1자리)
                    - 순위: 값이 같으면 먼저 달성한 사람이 앞(공동 순위 없음). 상위 % = max(1, ceil(순위 × 100 ÷ 참가자 수))
                    - 실시간: 순공·시간대는 진행 중 세션을 포함하고 focusing=true인 값은 asOf부터 1초씩 올려 보간한다. 15초 폴링 권장
                    - 판별 추가 필드: 집중률 eligibility·above.catchUpFocusSec, 누적 nextTier, 연속 일수 streak·aroundGroups \
                    (around 대신), 순공 주간에 기록이 없으면 goalExamples. 해당하지 않으면 null
                    - offset=-1은 직전 기간의 최종 결과(기간 판만). 없는 조합은 400""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/board", version = "1")
    public RankingBoardResponse board(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "종목", example = "FOCUS_TIME") @RequestParam RankingBoardType type,
            @Parameter(description = "기간 — 기간 판만", example = "WEEKLY") @RequestParam(required = false)
                    RankingPeriod period,
            @Parameter(description = "시간대 구간 — 시간대 판만, 생략하면 지금 구간") @RequestParam(required = false)
                    TimeSlot slot,
            @Parameter(description = "0=지금 기간, -1=직전 기간(기간 판만)", example = "0")
                    @RequestParam(defaultValue = "0")
                    int offset) {
        return rankingBoardService.board(userId, type, period, slot, offset);
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingBoardApiTest" --tests "project.study.config.*" --tests "project.study.ArchitectureTest"`
Expected: PASS (Swagger 버전 문서 테스트·아키텍처 테스트 포함)

- [ ] **Step 5: 커밋**

```bash
./gradlew spotlessApply && ./gradlew check
git add src/main/java/project/study/ranking/controller/ src/test/java/project/study/ranking/RankingBoardApiTest.java
git commit -m "feat: 랭킹판 조회 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: ADR·전체 검증·리뷰·PR

**Files:**
- Create: `docs/adr/0028-ranking-aggregate-on-read.md`

- [ ] **Step 1: ADR 작성**

`docs/adr/0028-ranking-aggregate-on-read.md`:

```markdown
# ADR-0028: 랭킹은 조회 때 집계하고 판 단위로 캐시한다

- 상태: 승인
- 날짜: 2026-10-10
- 티켓: BY-828 (스토리 BY-819, 짝 BY-827)
- 명세: `.ai/product/specs/BY-819-랭킹.md`
- 설계: `docs/superpowers/specs/2026-10-10-by828-ranking-design.md`

## 맥락

랭킹 탭은 순공(일·주·월)·집중률(주·월)·시간대 5구간(일·주) 랭킹판과 명예의 전당(누적 시간·누적 일수·연속 공부 일수)을
보여주고, 순공·시간대는 집중 중인 사람의 값이 실시간으로 오른다. FE는 랭킹 탭을 보는 동안 15초마다 폴링한다. 서버에는
확정 세션(`study_session`, 자정 분할 조각)과 진행 중 스냅샷(`active_study_session`, 30초 주기)이 있다. 규모 전제는 주간 활성
천~수천 명이다.

## 결정

1. **조회 때 SQL로 집계하고 판·기간 단위로 메모리 캐시한다.** 진행 중 기간 판 10초, 명예의 전당·지난 기간 60초. 같은 키
   동시 요청은 한 번만 계산한다. 점수 테이블을 쓰기 경로에서 갱신하는 안은 기각 — 자동 확정본 대체·삭제 때 증감이 어긋나는
   파생값 동기화 문제(ADR-0007)를 새로 들이고, 이 규모에선 이득이 없다.
2. **내 값은 캐시와 따로 매 요청 새로 읽는다.** 캐시된 순위표에서 내 옛 줄을 빼고 새 줄을 끼운다 — 세션을 막 끝낸 직후에도 내
   순위가 늦지 않는다.
3. **진행 중 세션은 draft를 확정과 같은 분할로 나눠 더한다.** 하트비트가 끊긴 draft도 자동 확정되면 같은 값이라 포함한다.
   "집중 중"은 마지막 수신 60초 이내 + 진행 중 이벤트 없음이고, 집중 중이면 마지막 수신 뒤 경과만큼 늘린 가상 스냅샷으로
   계산한다. 캐시된 값도 요청 시각까지 올려 다시 정렬한다. 명예의 전당은 확정 세션만 본다.
4. **시간대 순공은 세션 저장 때 구간별로 나눠 `study_session_slot`에 둔다**(V26). 조각 순공을 구간 경계(KST 04·07·12·18·22시)로
   이벤트 제외 길이 비율로 배분한다 — 자정 분할과 같은 규칙(`computeSegmentWeights`)이라 구간 합이 조각 순공과 같다. 심야는
   시작한 날에 귀속한다. 세션의 값 컬렉션이라 대체·삭제가 함께 움직인다(ADR-0023 §6과 같은 근거). 조회 때 이벤트를 조인해
   계산하는 안은 확정 세션(SQL)과 draft(Java)에 같은 규칙을 두 번 구현하게 돼 기각했다. 이번 주 세션은 기동 때 멱등 러너로 채운다.
5. **숫자는 다른 화면과 같은 기준이다.** 순공 1분 미만 조각 제외, 누적 일수는 누적 공부일 API, 연속 일수는 스트릭 `maxStreak`.
   탈퇴자는 뺀다. 동점은 값 → 먼저 도달(값을 만든 마지막 조각의 종료 시각) → userId 순이다.
6. **다른 사용자의 userId는 응답에 싣지 않는다.** 순차 ID라 사용자 규모가 드러난다. 상위 %는 정수
   (`max(1, ceil(순위 × 100 ÷ 참가자))`)로, 순위와 함께 보면 인원을 추정할 수 있음을 수용했다.
7. **순공·총공부는 계속 앱 값을 믿는다.** ADR-0006·0008이 "랭킹 도입 때 재검토"로 남긴 유보는 유지하고, 부정 사용 대응은
   명세의 추후 과제로 둔다.

## 결과

- `GET /api/rankings/board` 하나로 모든 판을 조회한다. 마감 기록(PR ②)과 노출용 API(PR ③)는 같은 엔진
  (`StandingsProvider`·`StandingsCalculator`)을 쓴다.
- 캐시는 태스크 메모리다. 태스크가 늘면 각자 계산할 뿐 결과는 같다.
- 확장 경로: 사용자가 만 명대가 되어 집계가 아프면 ① 판별 점수 테이블(쓰기 경로 갱신) → ② Redis 정렬 집합 순으로 옮긴다.
- 세션 저장마다 조각당 구간 행이 최대 6개 더 생긴다.
```

- [ ] **Step 2: 전체 검증**

Run: `./gradlew spotlessApply && ./gradlew check`
Expected: BUILD SUCCESSFUL (테스트·Spotless·checkstyle·ArchUnit 전부)

- [ ] **Step 3: 커밋**

```bash
git add docs/adr/0028-ranking-aggregate-on-read.md
git commit -m "docs: 랭킹 집계 방식을 ADR로 남긴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: 기동 확인**

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
# 다른 터미널
curl -s localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`, 로그에 Flyway V26 적용과 (기존 로컬 데이터가 있으면) `시간대 구간 백필: N개 세션`

- [ ] **Step 5: 2차 리뷰 (CLAUDE.md 크로스 코드체크)**

```bash
codex exec -s read-only "git diff dev...HEAD 를 리뷰해줘. BY-828 랭킹 집계 엔진(PR ①)이고 설계는 docs/superpowers/specs/2026-10-10-by828-ranking-design.md, ADR은 docs/adr/0028-ranking-aggregate-on-read.md다. 집계 SQL의 정확성(1분 기준·탈퇴 제외·동점), 진행 중 draft 분할과 집중 중 판정, 캐시 동시성, 다른 사용자 userId 노출 여부를 중점으로 P1/P2/P3로 분류해줘."
```
Expected: P1이 없을 것. P1이 있으면 고치고 Step 2부터 다시.

- [ ] **Step 6: 퀴즈 게이트 (CLAUDE.md 7번)**

사용자에게 구현 코드·흐름 퀴즈 5개를 낸다(예: 캐시된 순위표에 내 옛 줄이 남아 있을 때 무슨 일이 일어나는가, 집중 중 판정 조건, 시간대 배분이 자정 분할과 같은 규칙인 이유 등). 못 맞추면 다른 퀴즈를 낸다. 사용자가 생략을 명시하면 건너뛴다.

- [ ] **Step 7: push·PR**

PR 본문은 `.github/pull_request_template.md`의 절 구조를 그대로 두고 절마다 짧게 채워 스크래치패드의 `pr-body.md`에 쓴다
(요약: V26 구간 테이블·백필, 집계 쿼리, draft 조각, 순위표·캐시, `GET /api/rankings/board`, ADR-0028 / 테스트: `./gradlew check` 통과).
attribution 푸터는 넣지 않는다(사용자 규칙).

```bash
git push -u origin feature/BY-819-ranking
gh pr create --base dev --title "[feat] BY-828 랭킹 집계 엔진과 랭킹판 조회 API" --body-file <스크래치패드>/pr-body.md
```
지라 BY-828은 PR ②·③이 남아 진행 중으로 둔다.
