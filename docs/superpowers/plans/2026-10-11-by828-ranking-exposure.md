# 랭킹 노출 API — 홈 카드·첫 접속 추월·세션 뒤 오른 랭킹 (BY-828 PR ③) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 홈 한 줄 카드와 첫 접속 추월 팝업(`GET /api/rankings/home?since=`), 공부 결과 화면의 "랭킹이 올랐어요"(`GET /api/rankings/session-gains?startedAt=`)를 내려준다.

**Architecture:** PR ①의 엔진을 그대로 쓴다. 홈 카드는 주간 순공 판의 `StandingsProvider.view`, 추월은 같은 주의 값을 `since` 시점으로 되돌린
순위표(끝난 조각은 전부, 걸친 조각은 시간 비율 — 진행 중 draft 포함)와 지금 순위표를 비교한다. 세션 뒤 오른 랭킹은 판마다 "이번 제출의 조각을 뺀
내 값"을 같은 엔진으로 다시 계산해, 지금 다른 사람들 사이에서 매긴 순위와 비교한다. 새 테이블은 없다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL 17, `JdbcClient`, Jackson 3(`tools.jackson`), JUnit 5 + AssertJ, `MockMvcTester`, Testcontainers 2.

**Spec:** `docs/superpowers/specs/2026-10-10-by828-ranking-design.md` (§2 결정 8·10, §3 집계 규칙, §4 진행 중 세션, §7.3 노출용 API, §10 오류·경계). 엔진·결정은 `docs/adr/0028-ranking-aggregate-on-read.md`.

## Global Constraints

- 새 의존성 없음. `SecurityConfig` 변경 없음. 마이그레이션 없음.
- 새 엔드포인트는 `version = "1"`. 테스트는 `.header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)`를 `.with(asUser(...))`보다 먼저 싣는다 — `asUser`는 기본으로 2를 싣는다.
- **`StandingsCalculator.compute`·`pastTotals`·`StandingsProvider.view`를 부르는 서비스는 트랜잭션을 걸지 않는다**(ADR-0028 결정 8 — 바깥 트랜잭션 안에서 부르면 `IllegalStateException`). 해당: `RankingHomeService`, `RankingSessionGainsService`. 엔진의 새 메서드도 `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)` + `requireRepeatableRead()`.
- 다른 사용자의 `userId`를 응답에 싣지 않는다(행 키는 `nickname`).
- 집계 기준은 PR ①과 같다: 순공 1분 미만 조각 제외(`MIN_LIST_FOCUS_SEC`, 조각 전체 기준), 탈퇴(`DELETE`) 제외, 정렬 키 값 내림차순 → 도달 시각 오름차순 → userId.
- 기간 경계는 KST. 주간은 월요일 00:00 ~ 다음 월요일 00:00.
- timestamptz는 JDBC에 `instant.atOffset(ZoneOffset.UTC)`로 넘기고 `rs.getObject(col, OffsetDateTime.class)`로 읽는다.
- **PR ②(#85, `feature/BY-819-ranking-records`)가 아직 dev에 없다.** 이 브랜치에는 `RankingBoard.closable()`·`RankingSource.settledPieces`·V27이 없으니 쓰지 않는다. 머지 충돌을 줄이려고:
  - `RankingIntegrationTestBase`는 고치지 않는다.
  - `RankingSource`에는 새 메서드를 **클래스 맨 끝**에만 붙인다.
  - `ActiveStudySessionService`는 `new LivePiece(...)` 호출 인자만 바꾼다.
  - spec은 §7.3만 고친다(7번째 상태 줄은 건드리지 않는다).
- checkstyle(테스트 포함): 파일 400줄, 메서드 60줄, 순환 복잡도 10, 파라미터 7개 이하, 안 쓰는 import 금지. 포맷은 `./gradlew spotlessApply`.
- CLAUDE.md: DTO는 record, 생성자 주입만, 커밋은 `./gradlew check` 통과 상태에서만. 커밋 메시지 끝에 `Co-Authored-By:` 줄.
- 퀴즈 게이트(CLAUDE.md 7번)는 Task 5에서 push·PR 전에 한 번 한다. 사용자가 생략을 명시하면 건너뛴다.

## 설계 보충 (spec에 없어 이 계획이 정한 것)

| 항목 | 정한 것 | 이유 |
|---|---|---|
| `since` 값 복원 | 조각 단위: `ended ≤ since`면 전부, `started ≥ since`면 0, 걸치면 `floor(focus × (since − started) ÷ (ended − started))`. 1분 기준은 조각 전체에 건다 | spec §7.3 "걸쳐 있던 조각은 시간 비율". 기준이 판과 같아야 같은 사람들이 나온다 |
| 진행 중 조각의 시각 | `LivePiece`에 `startedAt`·`endedAt`(분할 조각의 시작·끝)을 더한다 | draft도 같은 비율 규칙을 쓰려면 조각 시각이 필요하다 |
| `since` 시점 도달 시각 | 그때까지 쌓인 마지막 순간(`min(ended, since)`의 최댓값) | 동점 규칙(먼저 도달)을 그 시점에도 적용 |
| `studiedFrom` | `since` 뒤에 끝나는 조각 중 `max(started, since)`의 최솟값 — `since`에 공부 중이었으면 `since` | "since 이후 처음 공부를 시작한 시각" |
| `studiedFocusSec` | 지금 값 − `since` 시점 값 | "since 이후 쌓은 순공" |
| 추월 null | `since` 없음·이번 주 월요일 00:00 이전·미래, 지금 이번 주 기록 없음, `since`에 내 순위 없음, 순위가 안 내려감 | spec 조건 + "since에 순위가 없으면 내려갈 순위도 없다" |
| 추월한 사람 | 지금 내 앞인 사람 중 `since`에 내 앞이 아니던 사람(그때 미참가 포함) | spec §7.3 |
| `nearest` | 그중 지금 나와 가장 가까운 2명, 가까운 순 | spec §7.3 |
| 오른 랭킹 "전" 값 | 같은 엔진으로 이번 제출(`submission_started_at`)의 확정 조각을 빼고 다시 계산(명예의 전당 포함). 진행 중 조각은 전·후 모두 같게 둔다 | "내 값에서 이 제출의 기여분을 뺀 값"을 판마다 같은 규칙으로 |
| 오른 랭킹 대상 판 | 지금 기간의 순공 일·주·월, 집중률 주·월, 이번 제출의 구간 행에 있는 시간대 일·주, 명예의 전당 3개 — 이 순서 | spec §7.3 대상과 칩 순서 |
| 새 ADR | 없음 — spec §7.3과 이 표가 결정이고, 엔진 결정은 ADR-0028이 덮는다. spec §7.3에 이 표의 핵심을 옮긴다 | |

## Review Focus

1. **`since`에 걸쳐 있던 조각(확정·진행 중)** — 시간 비율만큼만 그때 값에 들어가고 `studiedFrom`은 `since`여야 한다. → Task 1·3 테스트.
2. **`since`에 아직 참가하지 않았던 사람이 지금 내 앞** — 추월한 사람으로 세야 한다. → Task 3 테스트.
3. **`since`가 이번 주 밖이거나 미래, `since`에 내 순위가 없음** — 추월은 null이고 카드는 그대로. → Task 3 테스트.
4. **이번 세션으로 처음 순위가 생긴 판** — 오른 랭킹에 넣지 않는다. → Task 4 테스트.
5. **세션 뒤 다른 사람과 값이 같아짐** — 먼저 도달한 사람이 앞이므로 "후" 순위가 그 규칙을 따라야 한다. → Task 4 테스트.

---

## 실행 전 준비

- [ ] 브랜치 확인: `git branch --show-current` → `feature/BY-819-ranking-exposure`, `git log --oneline -1` → `d56ceb5` 또는 그 뒤(이 계획 커밋).
- [ ] 마이그레이션은 없다. 지라 BY-828은 이미 진행 중이다.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `studysession/dto/LivePiece.java` (수정) | `startedAt`·`endedAt` 추가 |
| `studysession/service/ActiveStudySessionService.java` (수정) | 조각 시각을 `LivePiece`에 싣는다 |
| `studysession/dto/RankingPastRow.java` | `since` 시점 합계 행 |
| `studysession/repository/StudySessionRankingQueries.java` (수정) | `periodTotalsAt`, 제출 제외 오버로드 4개 |
| `studysession/service/RankingSource.java` (수정, 끝에 추가) | 위 쿼리 위임, `submissionSlots` |
| `ranking/engine/PastEntry.java` | `since` 시점 한 줄과 진행 중 조각 되돌리기 |
| `ranking/engine/StandingsCalculator.java` (수정) | `pastTotals`, 제출 제외 `compute` |
| `ranking/dto/RankingHomeResponse.java` | 홈 응답 |
| `ranking/service/RankingHomeService.java` | 카드·추월 |
| `ranking/dto/RankingSessionGainsResponse.java` | 오른 랭킹 응답 |
| `ranking/service/RankingSessionGainsService.java` | 판별 전·후 순위 |
| `ranking/controller/RankingController.java` (수정) | `/home`, `/session-gains` |

---

### Task 1: `since` 시점 값 복원 (엔진)

**Files:**
- Modify: `src/main/java/project/study/studysession/dto/LivePiece.java`
- Modify: `src/main/java/project/study/studysession/service/ActiveStudySessionService.java` (`toLivePieces`의 `new LivePiece(...)`)
- Create: `src/main/java/project/study/studysession/dto/RankingPastRow.java`
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java` (`periodTotalsAt`)
- Modify: `src/main/java/project/study/studysession/service/RankingSource.java` (끝에 `periodTotalsAt`)
- Create: `src/main/java/project/study/ranking/engine/PastEntry.java`
- Modify: `src/main/java/project/study/ranking/engine/StandingsCalculator.java` (`pastTotals`)
- Create: `src/test/java/project/study/ranking/engine/PastTotalsTest.java`

**Interfaces:**
- Consumes: `StandingsCalculator`의 private `requireRepeatableRead()`·`stillOpen(List<LivePiece>)`·`within(Window, LocalDate)`, `RankingSource.activeNicknames(Collection<Long>)`, `RankingCalendar.Window` (PR ①)
- Produces:
  - `LivePiece(..., long draftId, Instant startedAt, Instant endedAt)` — 마지막 두 컴포넌트 추가
  - `record RankingPastRow(long userId, String nickname, long focusSec, Instant achievedAt, Instant studiedFrom)` — achievedAt·studiedFrom은 null 가능
  - `List<RankingPastRow> StudySessionRankingQueries.periodTotalsAt(LocalDate from, LocalDate to, Instant since)` / 같은 시그니처의 `RankingSource.periodTotalsAt`
  - `record PastEntry(long userId, String nickname, long valueAt, Instant achievedAt, Instant studiedFrom)` — `static PastEntry of(LivePiece, Instant since)`, `PastEntry plus(PastEntry)`, `PastEntry withNickname(String)`, `RankingEntry toEntry()`
  - `List<PastEntry> StandingsCalculator.pastTotals(Window window, Instant since, List<LivePiece> live)` — 값이 0인 사람(since 뒤에만 공부)도 포함

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/engine/PastTotalsTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.service.RankingSource;

/** 주간 순공을 since 시점으로 되돌린다 — 끝난 조각은 전부, 걸친 조각은 시간 비율 (BY-828 §7.3). 기준 시각은 2026-10-10(토) 15:00 KST. */
class PastTotalsTest extends RankingIntegrationTestBase {

    private static final RankingBoard WEEKLY_FOCUS = new RankingBoard(FOCUS_TIME, WEEKLY, null);

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private RankingSource source;

    private List<PastEntry> pastAt(Instant since) {
        return calculator
                .pastTotals(RankingCalendar.window(WEEKLY_FOCUS, NOW, 0), since, source.livePieces(NOW))
                .stream()
                .sorted(Comparator.comparing(PastEntry::nickname))
                .toList();
    }

    @Test
    void 끝난_조각은_전부_걸친_조각은_시간_비율로_되돌리고_지난주는_뺀다() {
        long a = user("a");
        session(a, kst(10, 2, 9, 0), 60, 3000); // 지난주 — 빠진다
        session(a, kst(10, 6, 9, 0), 60, 3000); // since 전에 끝남 — 전부
        session(a, kst(10, 9, 9, 0), 120, 6000); // since(10:00)에 걸침 — 절반
        long b = user("b");
        session(b, kst(10, 9, 13, 0), 60, 2400); // since 뒤에만 — 0
        Instant since = kst(10, 9, 10, 0);

        assertThat(pastAt(since))
                .extracting(PastEntry::nickname, PastEntry::valueAt, PastEntry::achievedAt, PastEntry::studiedFrom)
                .containsExactly(tuple("a", 6000L, since, since), tuple("b", 0L, null, kst(10, 9, 13, 0)));
    }

    @Test
    void 진행_중_draft도_같은_규칙으로_되돌리고_탈퇴자는_빠진다() {
        long c = user("c");
        draft(c, kst(10, 10, 13, 0), kst(10, 10, 14, 58), 7080, "[]"); // 마지막 수신 2분 전 — 집중 중 아님(연장 없음)
        long gone = user("gone");
        draft(gone, kst(10, 10, 13, 0), kst(10, 10, 14, 58), 7080, "[]");
        withdraw(gone);

        assertThat(pastAt(kst(10, 10, 14, 0)))
                .extracting(PastEntry::nickname, PastEntry::valueAt, PastEntry::studiedFrom)
                .containsExactly(tuple("c", 3600L, kst(10, 10, 14, 0)));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.engine.PastTotalsTest"`
Expected: 컴파일 실패 — `PastEntry`·`pastTotals`가 없다.

- [ ] **Step 3: 진행 중 조각에 시각을 싣는다**

`LivePiece.java` — 레코드 컴포넌트 끝에 두 개를 더하고 Javadoc 끝에 한 문장을 붙인다:

```java
 * startedAt·endedAt은 분할된 조각의 시작·끝(집중 중이면 늘린 끝)이다 — 지난 시점 값을 시간 비율로 되돌릴 때 쓴다.
 */
public record LivePiece(
        long userId,
        LocalDate statDate,
        int focusSec,
        int studySec,
        List<SessionSlot> slots,
        boolean latest,
        boolean focusing,
        Instant achievedAt,
        long draftId,
        Instant startedAt,
        Instant endedAt) {}
```

`ActiveStudySessionService.toLivePieces`의 `new LivePiece(...)` 호출 끝에 두 인자를 더한다(다른 줄은 건드리지 않는다):

```java
                    achievedAt,
                    draft.getId(),
                    piece.getStartedAt(),
                    piece.getEndedAt()));
```

- [ ] **Step 4: `since` 시점 합계 쿼리**

`src/main/java/project/study/studysession/dto/RankingPastRow.java`:

```java
package project.study.studysession.dto;

import java.time.Instant;

/**
 * 기간 순공을 since 시점으로 되돌린 한 사용자의 합계 (BY-828 §7.3). achievedAt은 since까지 쌓인 마지막 순간(그 전에 공부가 없으면
 * null), studiedFrom은 since 뒤에 처음 공부한 시각(since에 공부 중이었으면 since, since 뒤 공부가 없으면 null)이다.
 */
public record RankingPastRow(long userId, String nickname, long focusSec, Instant achievedAt, Instant studiedFrom) {}
```

`StudySessionRankingQueries.java` — import에 `java.time.ZoneOffset`, `project.study.studysession.dto.RankingPastRow`를 더하고 `slotTotals` 뒤에 추가:

```java
    /**
     * 기간 순공을 since 시점으로 되돌린 합 — since까지 끝난 조각은 전부, 걸쳐 있던 조각은 시간 비율만큼 더한다(1분 기준은 조각
     * 전체에 건다). since 뒤에만 공부한 사람도 값 0으로 낸다 — 추월 팝업이 그 사람의 studiedFrom을 쓴다.
     */
    public List<RankingPastRow> periodTotalsAt(LocalDate from, LocalDate to, Instant since) {
        String sql = """
                SELECT s.user_id, u.nickname,
                       SUM(CASE WHEN COALESCE(s.ended_at, s.started_at) <= :since THEN s.focus_sec
                                WHEN s.started_at >= :since THEN 0
                                ELSE FLOOR(s.focus_sec * EXTRACT(EPOCH FROM (:since - s.started_at))
                                           / EXTRACT(EPOCH FROM (s.ended_at - s.started_at)))
                           END) AS focus_sec,
                       MAX(CASE WHEN s.started_at < :since
                                THEN LEAST(COALESCE(s.ended_at, s.started_at), :since) END) AS achieved_at,
                       MIN(CASE WHEN COALESCE(s.ended_at, s.started_at) > :since
                                THEN GREATEST(s.started_at, :since) END) AS studied_from
                FROM study_session s
                JOIN users u ON u.id = s.user_id
                WHERE s.stat_date BETWEEN :from AND :to
                  AND s.focus_sec >= :minFocusSec
                  AND %s
                GROUP BY s.user_id, u.nickname""".formatted(NOT_WITHDRAWN);
        return jdbc.sql(sql)
                .paramSource(range(null, from, to).addValue("since", since.atOffset(ZoneOffset.UTC)))
                .query((rs, i) -> new RankingPastRow(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getLong("focus_sec"),
                        nullableInstant(rs, "achieved_at"),
                        nullableInstant(rs, "studied_from")))
                .list();
    }
```

파일 끝의 `instant(...)` 옆에 추가:

```java
    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
```

`RankingSource.java` — **클래스 맨 끝**(`studiedDays` 뒤)에 추가하고 import에 `project.study.studysession.dto.RankingPastRow`를 더한다:

```java
    /** 기간 순공을 since 시점으로 되돌린 합 — 첫 접속 추월. */
    public List<RankingPastRow> periodTotalsAt(LocalDate from, LocalDate to, Instant since) {
        return queries.periodTotalsAt(from, to, since);
    }
```

- [ ] **Step 5: `PastEntry`와 `pastTotals`**

`src/main/java/project/study/ranking/engine/PastEntry.java`:

```java
package project.study.ranking.engine;

import java.time.Duration;
import java.time.Instant;
import project.study.studysession.dto.LivePiece;

/**
 * 기간 판의 since 시점 값 한 줄 (BY-828 §7.3, 첫 접속 추월). valueAt·achievedAt은 since까지, studiedFrom은 since 뒤에 처음 공부한
 * 시각이다(없으면 null). 진행 중 조각만 있는 사람은 닉네임을 나중에 채운다.
 */
public record PastEntry(long userId, String nickname, long valueAt, Instant achievedAt, Instant studiedFrom) {

    /** 진행 중 조각 하나를 since로 되돌린다 — 끝났으면 전부, 걸쳐 있으면 시간 비율만큼, since 뒤에 시작했으면 0. */
    static PastEntry of(LivePiece piece, Instant since) {
        Instant start = piece.startedAt();
        Instant end = piece.endedAt();
        long value;
        if (!end.isAfter(since)) {
            value = piece.focusSec();
        } else if (!start.isBefore(since)) {
            value = 0;
        } else {
            value = piece.focusSec()
                    * Duration.between(start, since).toSeconds()
                    / Duration.between(start, end).toSeconds();
        }
        Instant achievedAt = start.isBefore(since) ? earlier(end, since) : null;
        Instant studiedFrom = end.isAfter(since) ? later(start, since) : null;
        return new PastEntry(piece.userId(), null, value, achievedAt, studiedFrom);
    }

    PastEntry plus(PastEntry other) {
        return new PastEntry(
                userId,
                nickname != null ? nickname : other.nickname,
                valueAt + other.valueAt,
                later(achievedAt, other.achievedAt),
                earlier(studiedFrom, other.studiedFrom));
    }

    PastEntry withNickname(String newNickname) {
        return new PastEntry(userId, newNickname, valueAt, achievedAt, studiedFrom);
    }

    /** since 시점 순위표의 줄 — 값이 0이면 그때 참가자가 아니다(호출자가 거른다). */
    public RankingEntry toEntry() {
        return RankingEntry.of(userId, nickname, valueAt, achievedAt);
    }

    private static Instant earlier(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }
}
```

(`piece.focusSec()`는 int지만 `Duration...toSeconds()`가 long이라 곱셈은 long으로 된다 — 오버플로 없음.)

`StandingsCalculator.java` — import에 `java.util.ArrayList`, `project.study.studysession.dto.RankingPastRow`를 더하고 `rateTotals` 뒤에 추가:

```java
    /**
     * 기간 판의 since 시점 값 (BY-828 §7.3, 첫 접속 추월) — 확정 조각은 SQL로, 진행 중 조각은 같은 규칙(끝난 조각 전부, 걸친 조각은
     * 시간 비율)으로 되돌린다. compute와 같은 스냅샷 규칙으로 아직 확정되지 않은 draft의 조각만 더한다. 값이 0인 사람(since 뒤에만
     * 공부)도 돌려준다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PastEntry> pastTotals(Window window, Instant since, List<LivePiece> live) {
        requireRepeatableRead();
        Map<Long, PastEntry> byUser = new HashMap<>();
        for (RankingPastRow row : source.periodTotalsAt(window.start(), window.end(), since)) {
            byUser.put(
                    row.userId(),
                    new PastEntry(row.userId(), row.nickname(), row.focusSec(), row.achievedAt(), row.studiedFrom()));
        }
        for (LivePiece piece : stillOpen(live)) {
            if (piece.focusSec() >= MIN_LIST_FOCUS_SEC && within(window, piece.statDate())) {
                byUser.merge(piece.userId(), PastEntry.of(piece, since), PastEntry::plus);
            }
        }
        return withNicknames(byUser);
    }
```

`fillLiveOnlyNicknames` 뒤에 추가:

```java
    /** 진행 중 조각만 있는 사람의 닉네임을 채운다 — 탈퇴자와 닉네임 없는 사람은 빠진다. */
    private List<PastEntry> withNicknames(Map<Long, PastEntry> byUser) {
        List<Long> missing = byUser.values().stream()
                .filter(entry -> entry.nickname() == null)
                .map(PastEntry::userId)
                .toList();
        Map<Long, String> nicknames = missing.isEmpty() ? Map.of() : source.activeNicknames(missing);
        List<PastEntry> entries = new ArrayList<>(byUser.size());
        for (PastEntry entry : byUser.values()) {
            if (entry.nickname() != null) {
                entries.add(entry);
            } else if (nicknames.containsKey(entry.userId())) {
                entries.add(entry.withNickname(nicknames.get(entry.userId())));
            }
        }
        return entries;
    }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*" --tests "project.study.studysession.*"`
Expected: PASS (PR ①의 진행 중 조각·랭킹 테스트도 그대로 통과)

- [ ] **Step 7: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/studysession/dto/LivePiece.java \
        src/main/java/project/study/studysession/service/ActiveStudySessionService.java \
        src/main/java/project/study/studysession/dto/RankingPastRow.java \
        src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java \
        src/main/java/project/study/studysession/service/RankingSource.java \
        src/main/java/project/study/ranking/engine/PastEntry.java \
        src/main/java/project/study/ranking/engine/StandingsCalculator.java \
        src/test/java/project/study/ranking/engine/PastTotalsTest.java
git commit -m "feat: 주간 순공을 지난 시점 값으로 되돌리는 계산을 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 이번 제출을 뺀 내 값 (엔진)

**Files:**
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java` (제출 제외 오버로드 4개)
- Modify: `src/main/java/project/study/studysession/service/RankingSource.java` (끝에 오버로드 4개·`submissionSlots`)
- Modify: `src/main/java/project/study/ranking/engine/StandingsCalculator.java` (`compute` 6인자)
- Create: `src/test/java/project/study/ranking/engine/StandingsCalculatorExcludeTest.java`

**Interfaces:**
- Consumes: `StudySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(Long, Instant)`, `StudySession.getSlots()`·`SessionSlot.getSlot()` (PR ①)
- Produces:
  - `StudySessionRankingQueries`·`RankingSource`: `periodTotals(LocalDate, LocalDate, Long userId, Instant excludeSubmission)`, `slotTotals(TimeSlot, LocalDate, LocalDate, Long userId, Instant excludeSubmission)`, `studyDays(LocalDate today, Long userId, Instant excludeSubmission)`, `maxStreaks(LocalDate today, Long userId, Instant excludeSubmission)` — 기존 메서드는 `null`로 위임
  - `Optional<Set<TimeSlot>> RankingSource.submissionSlots(long userId, Instant submissionStartedAt)` — 내 제출이 없으면 empty
  - `List<RankingEntry> StandingsCalculator.compute(RankingBoard, Window, Instant asOf, List<LivePiece>, Long onlyUserId, Instant excludeSubmission)` — 기존 5인자는 `null`로 위임

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/engine/StandingsCalculatorExcludeTest.java`:

```java
package project.study.ranking.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.MAX_STREAK;
import static project.study.ranking.RankingBoardType.TIME_SLOT;
import static project.study.ranking.RankingBoardType.TOTAL_DAYS;
import static project.study.ranking.RankingBoardType.TOTAL_TIME;
import static project.study.ranking.RankingPeriod.DAILY;
import static project.study.ranking.RankingPeriod.WEEKLY;

import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingIntegrationTestBase;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/** 세션 뒤 오른 랭킹의 "이번 세션 전" 값 — 그 제출의 조각만 빼고 같은 엔진으로 계산한다 (BY-828 §7.3). */
class StandingsCalculatorExcludeTest extends RankingIntegrationTestBase {

    @Autowired
    private StandingsCalculator calculator;

    @Autowired
    private RankingSource source;

    private double valueWithout(RankingBoard board, long userId, Instant excluded) {
        return calculator
                .compute(board, RankingCalendar.window(board, NOW, 0), NOW, source.livePieces(NOW), userId, excluded)
                .stream()
                .findFirst()
                .map(RankingEntry::value)
                .orElse(0.0);
    }

    @Test
    void 제출을_빼면_그_제출의_조각만_빠진다() {
        long me = user("me");
        session(me, kst(10, 9, 9, 0), 60, 3000); // 금 오전
        Instant last = kst(10, 10, 9, 0);
        session(me, last, 60, 2000); // 토 오전 — 뺄 제출

        assertThat(valueWithout(new RankingBoard(FOCUS_TIME, WEEKLY, null), me, last)).isEqualTo(3000);
        assertThat(valueWithout(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.MORNING), me, last)).isEqualTo(3000);
        assertThat(valueWithout(new RankingBoard(TOTAL_TIME, null, null), me, last)).isEqualTo(3000);
        assertThat(valueWithout(new RankingBoard(TOTAL_DAYS, null, null), me, last)).isEqualTo(1);
        assertThat(valueWithout(new RankingBoard(MAX_STREAK, null, null), me, last)).isEqualTo(1);
        assertThat(valueWithout(new RankingBoard(MAX_STREAK, null, null), me, null)).isEqualTo(2);
        assertThat(valueWithout(new RankingBoard(FOCUS_TIME, DAILY, null), me, last)).isZero();
    }

    @Test
    void 제출이_지난_구간을_주고_내_제출이_아니면_비어_있다() {
        long me = user("me");
        long other = user("other");
        Instant start = kst(10, 10, 11, 30);
        session(me, start, 60, 3000); // 11:30–12:30 — 오전·오후

        assertThat(source.submissionSlots(me, start)).contains(EnumSet.of(TimeSlot.MORNING, TimeSlot.AFTERNOON));
        assertThat(source.submissionSlots(other, start)).isEmpty();
        assertThat(source.submissionSlots(me, start.plusSeconds(1))).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.engine.StandingsCalculatorExcludeTest"`
Expected: 컴파일 실패 — 6인자 `compute`·`submissionSlots`가 없다.

- [ ] **Step 3: 쿼리에 제출 제외를 더한다**

`StudySessionRankingQueries.java` — 네 메서드를 아래처럼 바꾼다. 기존 시그니처는 `null`로 위임하고, 새 오버로드가 SQL을 가진다.
SQL 본문은 `userFilter(userId)` 자리를 `userFilter(userId) + excludeFilter(excludeSubmission)`로, 파라미터 소스를 `exclude(..., excludeSubmission)`으로 감싸는 것만 바뀐다.

```java
    /** 기간(stat_date) 순공·총공부 합. 도달 시각은 그 값을 만든 마지막 조각의 종료 시각이다. */
    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId) {
        return periodTotals(from, to, userId, null);
    }

    /** excludeSubmission을 주면 그 제출(submission_started_at)의 조각을 뺀다 — 세션 뒤 오른 랭킹의 "이번 세션 전" 값. */
    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId, Instant excludeSubmission) {
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
                GROUP BY s.user_id, u.nickname"""
                .formatted(NOT_WITHDRAWN, userFilter(userId) + excludeFilter(excludeSubmission));
        return jdbc.sql(sql)
                .paramSource(exclude(range(userId, from, to), excludeSubmission))
                .query(TOTAL_ROW)
                .list();
    }
```

```java
    /** 시간대 구간 순공 합 — 구간 행은 조각 단위라 조각의 1분 기준을 그대로 건다. */
    public List<RankingTotalRow> slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId) {
        return slotTotals(slot, from, to, userId, null);
    }

    public List<RankingTotalRow> slotTotals(
            TimeSlot slot, LocalDate from, LocalDate to, Long userId, Instant excludeSubmission) {
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
                GROUP BY s.user_id, u.nickname"""
                .formatted(NOT_WITHDRAWN, userFilter(userId) + excludeFilter(excludeSubmission));
        return jdbc.sql(sql)
                .paramSource(exclude(range(userId, from, to).addValue("slot", slot.name()), excludeSubmission))
                .query(TOTAL_ROW)
                .list();
    }

    /** 누적 공부일 — 순공 1분 이상 조각이 있는 날 수(오늘까지). 누적 공부일 API와 같은 숫자다. */
    public List<RankingDaysRow> studyDays(LocalDate today, Long userId) {
        return studyDays(today, userId, null);
    }

    public List<RankingDaysRow> studyDays(LocalDate today, Long userId, Instant excludeSubmission) {
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
                GROUP BY d.user_id, u.nickname"""
                .formatted(userFilter(userId) + excludeFilter(excludeSubmission), NOT_WITHDRAWN);
        MapSqlParameterSource params =
                params(userId).addValue("today", today).addValue("minFocusSec", MIN_LIST_FOCUS_SEC);
        return jdbc.sql(sql)
                .paramSource(exclude(params, excludeSubmission))
                .query((rs, i) -> new RankingDaysRow(
                        rs.getLong("user_id"), rs.getString("nickname"), rs.getInt("days"), instant(rs, "achieved_at")))
                .list();
    }
```

`maxStreaks`도 같은 방식이다 — 기존 2인자 메서드의 Javadoc을 두고 몸통을 `return maxStreaks(today, userId, null);`로 바꾼 뒤, 기존 몸통을 그대로
옮긴 3인자 `maxStreaks(LocalDate today, Long userId, Instant excludeSubmission)`를 만들고 그 안의 두 곳만 바꾼다:

```java
                WHERE %s""".formatted(userFilter(userId) + excludeFilter(excludeSubmission), NOT_WITHDRAWN);
```

```java
        return jdbc.sql(sql)
                .paramSource(exclude(params, excludeSubmission))
```

(`days` CTE의 `... AND s.stat_date <= :today%s`가 첫 번째 `%s`다 — 제외 조건이 그 CTE에 들어가 연속 일수 계산 전체에서 그 제출의 조각이 빠진다.)

파일 끝 헬퍼 옆에 추가:

```java
    private static String excludeFilter(Instant excludeSubmission) {
        return excludeSubmission == null ? "" : " AND s.submission_started_at IS DISTINCT FROM :excludeSubmission";
    }

    private static MapSqlParameterSource exclude(MapSqlParameterSource params, Instant excludeSubmission) {
        return excludeSubmission == null
                ? params
                : params.addValue("excludeSubmission", excludeSubmission.atOffset(ZoneOffset.UTC));
    }
```

- [ ] **Step 4: 소스에 위임과 제출 구간 조회를 더한다**

`RankingSource.java` — **클래스 맨 끝**에 추가하고 import에 `java.util.EnumSet`, `java.util.Optional`, `project.study.studysession.entity.StudySession`을 더한다(`Set`·`TimeSlot`·`Instant`·`Transactional`은 이미 있다 — 없으면 더한다):

```java
    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId, Instant excludeSubmission) {
        return queries.periodTotals(from, to, userId, excludeSubmission);
    }

    public List<RankingTotalRow> slotTotals(
            TimeSlot slot, LocalDate from, LocalDate to, Long userId, Instant excludeSubmission) {
        return queries.slotTotals(slot, from, to, userId, excludeSubmission);
    }

    public List<RankingDaysRow> studyDays(LocalDate today, Long userId, Instant excludeSubmission) {
        return queries.studyDays(today, userId, excludeSubmission);
    }

    public List<RankingStreakRow> maxStreaks(LocalDate today, Long userId, Instant excludeSubmission) {
        return queries.maxStreaks(today, userId, excludeSubmission);
    }

    /** 한 제출(자정 분할 조각 전부)이 지난 시간대 구간 — 내 제출이 아니거나 없으면 empty. */
    @Transactional(readOnly = true)
    public Optional<Set<TimeSlot>> submissionSlots(long userId, Instant submissionStartedAt) {
        List<StudySession> pieces = studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(
                userId, submissionStartedAt);
        if (pieces.isEmpty()) {
            return Optional.empty();
        }
        Set<TimeSlot> slots = EnumSet.noneOf(TimeSlot.class);
        pieces.forEach(piece -> piece.getSlots().forEach(slot -> slots.add(slot.getSlot())));
        return Optional.of(slots);
    }
```

- [ ] **Step 5: 엔진에 제출 제외를 더한다**

`StandingsCalculator.java`:
- 기존 5인자 `compute`의 몸통을 6인자 `compute`로 옮기고, 5인자는 위임만 한다. 두 메서드 모두 `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`.
- 6인자 안에서 `accumulate(..., onlyUserId)` 호출에 `excludeSubmission`을 더하고, 명예의 전당 세 줄은 `source.periodTotals(RankingCalendar.ALL_TIME_START, window.end(), onlyUserId, excludeSubmission)`·`source.studyDays(window.end(), onlyUserId, excludeSubmission)`·`source.maxStreaks(window.end(), onlyUserId, excludeSubmission)`로 바꾼다.
- `accumulate`와 `finalizedRows`에 마지막 파라미터 `Instant excludeSubmission`을 더해 `source.slotTotals(..., onlyUserId, excludeSubmission)`·`source.periodTotals(..., onlyUserId, excludeSubmission)`로 넘긴다. `rateTotals`는 `accumulate(..., userId, null)`.

```java
    /** 판 전체(onlyUserId=null) 또는 한 사용자의 줄. live는 asOf에 나눈 진행 중 조각이다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RankingEntry> compute(
            RankingBoard board, Window window, Instant asOf, List<LivePiece> live, Long onlyUserId) {
        return compute(board, window, asOf, live, onlyUserId, null);
    }

    /**
     * excludeSubmission을 주면 그 제출(submission_started_at)의 확정 조각을 빼고 계산한다 — 세션 뒤 오른 랭킹의 "이번 세션 전" 내
     * 값(BY-828 §7.3). 진행 중 조각은 그대로 둔다. 한 사용자(onlyUserId)를 계산할 때 쓴다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RankingEntry> compute(
            RankingBoard board,
            Window window,
            Instant asOf,
            List<LivePiece> live,
            Long onlyUserId,
            Instant excludeSubmission) {
        requireRepeatableRead();
        return switch (board.type()) {
            case FOCUS_TIME, TIME_SLOT ->
                accumulate(board, window, asOf, live, onlyUserId, excludeSubmission).entrySet().stream()
                        .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().focusSec))
                        .toList();
            case FOCUS_RATE ->
                accumulate(board, window, asOf, live, onlyUserId, excludeSubmission).entrySet().stream()
                        .filter(e -> e.getValue().focusSec >= requiredRateFocusSec(board.period()))
                        .map(e -> e.getValue().toEntry(e.getKey(), e.getValue().rate()))
                        .toList();
            case TOTAL_TIME ->
                source
                        .periodTotals(RankingCalendar.ALL_TIME_START, window.end(), onlyUserId, excludeSubmission)
                        .stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.focusSec(), r.achievedAt()))
                        .toList();
            case TOTAL_DAYS ->
                source.studyDays(window.end(), onlyUserId, excludeSubmission).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                        .toList();
            case MAX_STREAK ->
                source.maxStreaks(window.end(), onlyUserId, excludeSubmission).stream()
                        .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.days(), r.achievedAt()))
                        .toList();
        };
    }
```

`rateTotals`는 `accumulate(board, window, asOf, live, userId, null)`로 부르고, `accumulate`·`finalizedRows`는 아래처럼 마지막 파라미터를 더한다
(`accumulate`의 나머지 몸통은 그대로):

```java
    private Map<Long, Totals> accumulate(
            RankingBoard board,
            Window window,
            Instant asOf,
            List<LivePiece> live,
            Long onlyUserId,
            Instant excludeSubmission) {
        Map<Long, Totals> totals = new HashMap<>();
        for (RankingTotalRow row : finalizedRows(board, window, onlyUserId, excludeSubmission)) {
            totals.put(row.userId(), new Totals(row.nickname(), row.focusSec(), row.studySec(), row.achievedAt()));
        }
        // 이하 기존 그대로 — stillOpen(piecesOf(live, onlyUserId)) 루프와 fillLiveOnlyNicknames
```

```java
    private List<RankingTotalRow> finalizedRows(
            RankingBoard board, Window window, Long onlyUserId, Instant excludeSubmission) {
        return board.type() == RankingBoardType.TIME_SLOT
                ? source.slotTotals(board.slot(), window.start(), window.end(), onlyUserId, excludeSubmission)
                : source.periodTotals(window.start(), window.end(), onlyUserId, excludeSubmission);
    }
```

(기존 동작은 `excludeSubmission = null`일 때 그대로다.)

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*" --tests "project.study.studysession.*"`
Expected: PASS

- [ ] **Step 7: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/studysession/repository/StudySessionRankingQueries.java \
        src/main/java/project/study/studysession/service/RankingSource.java \
        src/main/java/project/study/ranking/engine/StandingsCalculator.java \
        src/test/java/project/study/ranking/engine/StandingsCalculatorExcludeTest.java
git commit -m "feat: 한 제출을 뺀 내 랭킹 값을 계산한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 홈 API (한 줄 카드·첫 접속 추월)

**Files:**
- Create: `src/main/java/project/study/ranking/dto/RankingHomeResponse.java`
- Create: `src/main/java/project/study/ranking/service/RankingHomeService.java`
- Modify: `src/main/java/project/study/ranking/controller/RankingController.java` (`home`)
- Create: `src/test/java/project/study/ranking/RankingHomeApiTest.java`

**Interfaces:**
- Consumes: `StandingsProvider.view(RankingBoard, Window, long, Instant)` → `BoardView(standings, placement, live)`, `Placement.present()/me()/myIndex()/myRank()/above()/merged()`, `Standings.of`, `RankingCalendar.window` (PR ①); `StandingsCalculator.pastTotals`, `PastEntry.toEntry()` (Task 1)
- Produces:
  - `record RankingHomeResponse(HomeCard card, Overtaken overtaken)` + 중첩 `HomeCard(int rank, long value, HomeNeighbor above)`, `HomeNeighbor(String nickname, long gap)`, `Overtaken(int fromRank, int toRank, int count, List<Overtaker> nearest)`, `Overtaker(String nickname, long gap, Instant studiedFrom, long studiedFocusSec)`
  - `RankingHomeResponse RankingHomeService.home(long userId, Instant since)` — 트랜잭션 없음
  - `GET /api/rankings/home?since=` (version 1)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/RankingHomeApiTest.java`:

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

/** 홈 한 줄 카드와 첫 접속 추월 (BY-828 §7.3). 기준 시각은 2026-10-10(토) 15:00 KST — 이번 주는 10/5(월)부터다. */
class RankingHomeApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcRequestBuilder home(String query, long userId) {
        return mvc.get()
                .uri("/api/rankings/home" + query)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    @Test
    void 카드는_이번_주_순위와_바로_위_차이고_추월은_그때_뒤였거나_없던_사람이다() {
        long c = user("c");
        session(c, kst(10, 5, 13, 0), 120, 6000); // 그때도 지금도 내 앞
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 60, 3000);
        long a = user("a");
        session(a, kst(10, 6, 9, 0), 60, 2000); // 그때는 내 뒤
        session(a, kst(10, 7, 9, 0), 60, 3500); // since 뒤 — 지금 5500
        long b = user("b");
        session(b, kst(10, 8, 9, 0), 120, 4000); // 그때는 없었다

        assertThat(home("?since=2026-10-06T15:00:00Z", me)) // 수 00:00 KST
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.card.value", v -> assertThat(v).isEqualTo(3000))
                .hasPathSatisfying("$.card.above.nickname", v -> assertThat(v).isEqualTo("b"))
                .hasPathSatisfying("$.card.above.gap", v -> assertThat(v).isEqualTo(1000))
                .hasPathSatisfying("$.overtaken.fromRank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.toRank", v -> assertThat(v).isEqualTo(4))
                .hasPathSatisfying("$.overtaken.count", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.nearest[0].nickname", v -> assertThat(v).isEqualTo("b"))
                .hasPathSatisfying("$.overtaken.nearest[0].gap", v -> assertThat(v).isEqualTo(1000))
                .hasPathSatisfying("$.overtaken.nearest[0].studiedFrom", v -> assertThat(v).isEqualTo("2026-10-08T00:00:00Z"))
                .hasPathSatisfying("$.overtaken.nearest[0].studiedFocusSec", v -> assertThat(v).isEqualTo(4000))
                .hasPathSatisfying("$.overtaken.nearest[1].nickname", v -> assertThat(v).isEqualTo("a"))
                .hasPathSatisfying("$.overtaken.nearest[1].studiedFrom", v -> assertThat(v).isEqualTo("2026-10-07T00:00:00Z"))
                .hasPathSatisfying("$.overtaken.nearest[1].studiedFocusSec", v -> assertThat(v).isEqualTo(3500))
                .doesNotHavePath("$.overtaken.nearest[0].userId");
    }

    @Test
    void since에_걸쳐_공부_중이던_진행_중_세션은_시간_비율로_되돌리고_since부터_공부했다고_준다() {
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 80, 4000);
        long y = user("y");
        draft(y, kst(10, 10, 13, 0), kst(10, 10, 14, 58), 7080, "[]"); // 14:00엔 3600 — 내 뒤, 지금 7080 — 내 앞

        assertThat(home("?since=2026-10-10T05:00:00Z", me)) // 토 14:00 KST
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.fromRank", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.overtaken.toRank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken.count", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.overtaken.nearest[0].gap", v -> assertThat(v).isEqualTo(3080))
                .hasPathSatisfying("$.overtaken.nearest[0].studiedFrom", v -> assertThat(v).isEqualTo("2026-10-10T05:00:00Z"))
                .hasPathSatisfying("$.overtaken.nearest[0].studiedFocusSec", v -> assertThat(v).isEqualTo(3480));
    }

    // 이번 주 월요일 00:00 KST 직전, 미래(15:00:01 KST)
    @ParameterizedTest
    @ValueSource(strings = {"?since=2026-10-04T14:59:59Z", "?since=2026-10-10T06:00:01Z", ""})
    void since가_이번_주_밖이거나_미래거나_없으면_추월은_없고_카드는_준다(String query) {
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 60, 3000);
        session(user("a"), kst(10, 8, 9, 0), 60, 3500);

        assertThat(home(query, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void since에_내_순위가_없었거나_순위가_안_내려갔으면_추월은_없다() {
        long me = user("me");
        session(me, kst(10, 9, 9, 0), 60, 3000); // since(수 00:00)엔 기록 없음
        session(user("a"), kst(10, 8, 9, 0), 60, 3500);
        long solo = user("solo");
        session(solo, kst(10, 5, 9, 0), 60, 3000);

        assertThat(home("?since=2026-10-06T15:00:00Z", me))
                .bodyJson()
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
        assertThat(home("?since=2026-10-09T15:00:00Z", solo)) // 그때도 지금도 2위 — 안 내려감
                .bodyJson()
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void 이번_주_기록이_없으면_카드도_추월도_없다() {
        assertThat(home("?since=2026-10-06T15:00:00Z", user("me")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.card", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.overtaken", v -> assertThat(v).isNull());
    }

    @Test
    void since_형식이_틀리면_400이고_토큰이_없으면_401이다() {
        assertThat(home("?since=nope", user("me"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get()
                        .uri("/api/rankings/home")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

(두 번째 단언의 `solo`: since(토 00:00 KST)에도 지금도 a 3500·solo 3000·me 3000이다 — solo와 me는 값이 같고 solo가 먼저 도달(월)해
그때도 지금도 solo 2위 → 안 내려감.)

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingHomeApiTest"`
Expected: FAIL — `/api/rankings/home`이 없어 404.

- [ ] **Step 3: 응답 DTO**

`src/main/java/project/study/ranking/dto/RankingHomeResponse.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 홈 랭킹 (BY-828 §7.3) — 한 줄 카드(5b)와 첫 접속 추월(5a). 다른 사용자의 userId는 싣지 않는다. */
@Schema(description = "홈 랭킹 — 한 줄 카드와 첫 접속 추월")
public record RankingHomeResponse(
        @Schema(description = "이번 주 순공 순위 — 이번 주 기록이 없으면 null") HomeCard card,

        @Schema(description = "since 뒤로 나를 추월한 사람 — since가 없거나 이번 주 월요일 00:00 이전·미래이거나, since에 내 순위가 없었거나, 순위가 내려가지 않았으면 null")
        Overtaken overtaken) {

    public record HomeCard(int rank, @Schema(description = "순공(초)") long value, @Schema(description = "바로 위 — 1위면 null") HomeNeighbor above) {}

    public record HomeNeighbor(String nickname, @Schema(description = "순공 차이(초)") long gap) {}

    public record Overtaken(
            int fromRank,
            int toRank,
            @Schema(description = "since에 내 뒤였거나 참가 전이었다가 지금 내 앞인 사람 수") int count,
            @Schema(description = "그중 지금 나와 가장 가까운 2명, 가까운 순") List<Overtaker> nearest) {}

    public record Overtaker(
            String nickname,
            @Schema(description = "지금 순공 차이(초)") long gap,
            @Schema(description = "since 뒤에 처음 공부를 시작한 시각 — since에 공부 중이었으면 since") Instant studiedFrom,
            @Schema(description = "since 뒤로 쌓은 순공(초)") long studiedFocusSec) {}
}
```

- [ ] **Step 4: 서비스**

`src/main/java/project/study/ranking/service/RankingHomeService.java`:

```java
package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingHomeResponse;
import project.study.ranking.dto.RankingHomeResponse.HomeCard;
import project.study.ranking.dto.RankingHomeResponse.HomeNeighbor;
import project.study.ranking.dto.RankingHomeResponse.Overtaken;
import project.study.ranking.dto.RankingHomeResponse.Overtaker;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.PastEntry;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.engine.StandingsProvider;

/**
 * 홈 랭킹 (BY-828 §7.3) — 이번 주 순공 판의 한 줄 카드와, since 뒤로 나를 추월한 사람. 트랜잭션을 걸지 않는다 — 순위표 계산이
 * 자체 REPEATABLE_READ 트랜잭션을 연다(ADR-0028 결정 8).
 */
@Service
@RequiredArgsConstructor
public class RankingHomeService {

    static final int NEAREST = 2;

    private static final RankingBoard WEEKLY_FOCUS =
            new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.WEEKLY, null);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StandingsProvider provider;
    private final StandingsCalculator calculator;
    private final Clock clock;

    public RankingHomeResponse home(long userId, Instant since) {
        Instant now = clock.instant();
        Window window = RankingCalendar.window(WEEKLY_FOCUS, now, 0);
        BoardView view = provider.view(WEEKLY_FOCUS, window, userId, now);
        if (!view.placement().present()) {
            return new RankingHomeResponse(null, null);
        }
        return new RankingHomeResponse(card(view.placement()), overtaken(view, window, userId, since, now));
    }

    private static HomeCard card(Placement placement) {
        RankingEntry me = placement.me();
        RankingEntry above = placement.above();
        return new HomeCard(
                placement.myRank(),
                Math.round(me.value()),
                above == null ? null : new HomeNeighbor(above.nickname(), gap(above, me)));
    }

    /** since 시점 순위표를 되돌려 지금과 비교한다 — 그때 내 앞이 아니던(미참가 포함) 사람 중 지금 내 앞인 사람이 추월한 사람이다. */
    private Overtaken overtaken(BoardView view, Window window, long userId, Instant since, Instant now) {
        Instant weekStart = window.start().atStartOfDay(KST).toInstant();
        if (since == null || since.isBefore(weekStart) || since.isAfter(now)) {
            return null;
        }
        Map<Long, PastEntry> past = calculator.pastTotals(window, since, view.live().pieces()).stream()
                .collect(Collectors.toMap(PastEntry::userId, Function.identity()));
        List<RankingEntry> then = Standings.of(
                        past.values().stream()
                                .filter(entry -> entry.valueAt() > 0)
                                .map(PastEntry::toEntry)
                                .toList(),
                        since)
                .entries();
        int fromIndex = indexOf(then, userId);
        Placement placement = view.placement();
        if (fromIndex < 0 || placement.myIndex() <= fromIndex) {
            return null;
        }
        Set<Long> aheadThen =
                then.subList(0, fromIndex).stream().map(RankingEntry::userId).collect(Collectors.toSet());
        List<RankingEntry> overtakers = placement.merged().subList(0, placement.myIndex()).stream()
                .filter(entry -> !aheadThen.contains(entry.userId()))
                .toList();
        return new Overtaken(
                fromIndex + 1,
                placement.myRank(),
                overtakers.size(),
                nearest(overtakers, placement.me(), past, since));
    }

    /** 추월한 사람 중 지금 나와 가장 가까운 순 — 바로 위부터 위로 올라간다. */
    private static List<Overtaker> nearest(
            List<RankingEntry> overtakers, RankingEntry me, Map<Long, PastEntry> past, Instant since) {
        List<Overtaker> rows = new ArrayList<>(NEAREST);
        for (int i = overtakers.size() - 1; i >= 0 && rows.size() < NEAREST; i--) {
            RankingEntry other = overtakers.get(i);
            PastEntry then = past.get(other.userId());
            long valueThen = then == null ? 0 : then.valueAt();
            Instant studiedFrom = then == null || then.studiedFrom() == null ? since : then.studiedFrom();
            rows.add(new Overtaker(
                    other.nickname(),
                    gap(other, me),
                    studiedFrom,
                    Math.max(0, Math.round(other.value()) - valueThen)));
        }
        return rows;
    }

    private static int indexOf(List<RankingEntry> entries, long userId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).userId() == userId) {
                return i;
            }
        }
        return -1;
    }

    private static long gap(RankingEntry ahead, RankingEntry me) {
        return Math.round(ahead.value() - me.value());
    }
}
```

- [ ] **Step 5: 컨트롤러**

`RankingController.java` — 필드에 `private final RankingHomeService rankingHomeService;`를, import에 `java.time.Instant`, `project.study.ranking.dto.RankingHomeResponse`, `project.study.ranking.service.RankingHomeService`를 더하고 `board` 뒤에 추가:

```java
    @Operation(summary = "홈 랭킹 (한 줄 카드·첫 접속 추월)", description = """
                    이번 주 순공 판의 내 순위·값·바로 위와의 차이(card)와, since 뒤로 나를 추월한 사람(overtaken)을 준다.

                    - card: 이번 주 기록이 없으면 null
                    - overtaken: since를 줬을 때만. since가 이번 주 월요일 00:00(KST) 이전이거나 미래이거나, since에 내 순위가 없었거나, \
                    순위가 내려가지 않았으면 null
                    - since 시점 값은 그때까지 끝난 조각과, 걸쳐 있던 조각(진행 중 세션 포함)의 시간 비율로 되돌린다
                    - nearest: 나를 추월한 사람 중 지금 가장 가까운 2명 — since 뒤에 처음 공부한 시각과 그 뒤로 쌓은 순공
                    - FE 몫: 마지막 포그라운드 시각 보관, 그날 첫 접속 판단, 하루 1회, 마감 모달이 뜬 날 생략""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/home", version = "1")
    public RankingHomeResponse home(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "마지막으로 앱을 본 시각(UTC ISO-8601)", example = "2026-10-07T12:00:00Z")
                    @RequestParam(required = false)
                    Instant since) {
        return rankingHomeService.home(userId, since);
    }
```

(Spring이 `Instant` 쿼리 파라미터를 ISO-8601로 바꾸지 못해 400 테스트가 아닌 다른 테스트가 깨지면, 파라미터에
`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)`을 더한다 — 리포트에 적는다.)

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 7: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/dto/RankingHomeResponse.java \
        src/main/java/project/study/ranking/service/RankingHomeService.java \
        src/main/java/project/study/ranking/controller/RankingController.java \
        src/test/java/project/study/ranking/RankingHomeApiTest.java
git commit -m "feat: 홈 랭킹 카드와 첫 접속 추월 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 세션 뒤 오른 랭킹 API

**Files:**
- Create: `src/main/java/project/study/ranking/dto/RankingSessionGainsResponse.java`
- Create: `src/main/java/project/study/ranking/service/RankingSessionGainsService.java`
- Modify: `src/main/java/project/study/ranking/controller/RankingController.java` (`sessionGains`)
- Create: `src/test/java/project/study/ranking/RankingSessionGainsApiTest.java`

**Interfaces:**
- Consumes: `StandingsProvider.view`, `BoardView.standings()/placement()/live()`, `Standings.place(long, RankingEntry).myRank()`, `RankingEntry.advancedTo(Instant, Instant)`, `LiveSnapshot.asOf()/pieces()` (PR ①); `StandingsCalculator.compute(..., excludeSubmission)`, `RankingSource.submissionSlots` (Task 2)
- Produces:
  - `record RankingSessionGainsResponse(Gain weekly, List<BoardGain> others)` + 중첩 `Gain(int before, int after, int delta)`, `BoardGain(RankingBoardType type, RankingPeriod period, TimeSlot slot, int before, int after, int delta)`
  - `RankingSessionGainsResponse RankingSessionGainsService.gains(long userId, Instant submissionStartedAt)` — 트랜잭션 없음, 내 제출이 없으면 `NotFoundException`
  - 패키지 전용 `static List<RankingBoard> RankingSessionGainsService.targets(Set<TimeSlot> slots)`
  - `GET /api/rankings/session-gains?startedAt=` (version 1)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/RankingSessionGainsApiTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

/** 공부 결과 화면 "랭킹이 올랐어요" (BY-828 §7.3). 기준 시각은 2026-10-10(토) 15:00 KST. */
class RankingSessionGainsApiTest extends RankingIntegrationTestBase {

    @Autowired
    private MockMvcTester mvc;

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    private MockMvcRequestBuilder gains(String query, long userId) {
        return mvc.get()
                .uri("/api/rankings/session-gains" + query)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    @Test
    void 오른_판만_칩_순서로_주고_처음_순위가_생긴_판과_안_오른_판은_뺀다() {
        session(user("a"), kst(10, 6, 9, 0), 120, 5000); // 화 오전 — 이번 세션 뒤 나와 같은 값이지만 먼저 도달
        session(user("b"), kst(10, 7, 9, 0), 90, 3500); // 수 오전
        long me = user("me");
        session(me, kst(10, 5, 9, 0), 60, 2000); // 월 오전 — 이번 세션 전엔 3위
        session(me, kst(10, 10, 9, 0), 60, 3000); // 토 오전 — 이번 제출, 이후 5000으로 2위

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.weekly.before", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.weekly.after", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.weekly.delta", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.others.length()", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.others[0].type", v -> assertThat(v).isEqualTo("FOCUS_TIME"))
                .hasPathSatisfying("$.others[0].period", v -> assertThat(v).isEqualTo("MONTHLY"))
                .hasPathSatisfying("$.others[1].type", v -> assertThat(v).isEqualTo("TIME_SLOT"))
                .hasPathSatisfying("$.others[1].period", v -> assertThat(v).isEqualTo("WEEKLY"))
                .hasPathSatisfying("$.others[1].slot", v -> assertThat(v).isEqualTo("MORNING"))
                .hasPathSatisfying("$.others[1].before", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.others[1].after", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.others[2].type", v -> assertThat(v).isEqualTo("TOTAL_TIME"))
                .hasPathSatisfying("$.others[2].period", v -> assertThat(v).isNull());
    }

    @Test
    void 이번_세션으로_처음_순위가_생긴_판뿐이면_weekly는_null이고_others는_비어_있다() {
        session(user("a"), kst(10, 6, 9, 0), 60, 3000);
        long me = user("me");
        session(me, kst(10, 10, 9, 0), 60, 2000);

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.weekly", v -> assertThat(v).isNull())
                .extractingPath("$.others")
                .asArray()
                .isEmpty();
    }

    @Test
    void 없는_제출이나_남의_제출이면_404다() {
        long me = user("me");
        long other = user("other");
        session(other, kst(10, 10, 9, 0), 60, 2000);

        assertThat(gains("?startedAt=2026-10-10T00:00:00Z", me)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(gains("?startedAt=2026-10-10T00:00:01Z", other)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void startedAt이_없거나_형식이_틀리면_400이고_토큰이_없으면_401이다() {
        long me = user("me");

        assertThat(gains("", me)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(gains("?startedAt=nope", me)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get()
                        .uri("/api/rankings/session-gains?startedAt=2026-10-10T00:00:00Z")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

(첫 테스트의 값: 이번 세션 전 me 2000 — a 5000·b 3500 다음 3위. 이번 세션 뒤 me 5000 — a와 같지만 a가 화요일에 먼저 도달해 a 1위·me 2위.
순공 일간·시간대 일간 오전은 이번 세션 전에 기록이 없어 빠지고, 누적 일수(me 2일, 전 1일 — 둘 다 1위)·연속 일수(1일, 1위)는 안 올라 빠진다.)

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingSessionGainsApiTest"`
Expected: FAIL — `/api/rankings/session-gains`가 없어 404(마지막 테스트의 400 단언 등).

- [ ] **Step 3: 응답 DTO**

`src/main/java/project/study/ranking/dto/RankingSessionGainsResponse.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 세션 뒤 오른 랭킹 (BY-828 §7.3) — 이번 제출 전보다 순위가 오른 판만. */
@Schema(description = "세션 뒤 오른 랭킹 — 오른 판만")
public record RankingSessionGainsResponse(
        @Schema(description = "주간 순공 — 오르지 않았으면 null") Gain weekly,
        @Schema(description = "나머지 오른 판 — 순공 일·월, 집중률 주·월, 시간대 일·주, 명예의 전당 순") List<BoardGain> others) {

    public record Gain(
            @Schema(description = "이번 제출을 뺀 내 값의 순위") int before,
            @Schema(description = "지금 순위") int after,
            int delta) {}

    public record BoardGain(
            RankingBoardType type,
            @Schema(description = "명예의 전당은 null") RankingPeriod period,
            @Schema(description = "시간대 판만") TimeSlot slot,
            int before,
            int after,
            int delta) {}
}
```

- [ ] **Step 4: 서비스**

`src/main/java/project/study/ranking/service/RankingSessionGainsService.java`:

```java
package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.exception.NotFoundException;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.RankingPeriod;
import project.study.ranking.dto.RankingSessionGainsResponse;
import project.study.ranking.dto.RankingSessionGainsResponse.BoardGain;
import project.study.ranking.dto.RankingSessionGainsResponse.Gain;
import project.study.ranking.engine.BoardView;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.engine.StandingsProvider;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.service.RankingSource;

/**
 * 세션 뒤 오른 랭킹 (BY-828 §7.3) — 판마다 "이번 제출을 뺀 나"와 "지금의 나"를 지금의 같은 다른 사람들 사이에서 매긴다. 이번 세션 전에
 * 순위가 없던 판은 넣지 않는다. 트랜잭션을 걸지 않는다 — 순위표 계산이 자체 REPEATABLE_READ 트랜잭션을 연다(ADR-0028 결정 8).
 */
@Service
@RequiredArgsConstructor
public class RankingSessionGainsService {

    private static final RankingBoard WEEKLY_FOCUS =
            new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.WEEKLY, null);

    private final StandingsProvider provider;
    private final StandingsCalculator calculator;
    private final RankingSource source;
    private final Clock clock;

    public RankingSessionGainsResponse gains(long userId, Instant submissionStartedAt) {
        Set<TimeSlot> slots = source.submissionSlots(userId, submissionStartedAt)
                .orElseThrow(() -> new NotFoundException("이 시각에 시작한 내 세션이 없습니다"));
        Instant now = clock.instant();
        Gain weekly = null;
        List<BoardGain> others = new ArrayList<>();
        for (RankingBoard board : targets(slots)) {
            Gain gain = gain(board, userId, submissionStartedAt, now);
            if (gain == null) {
                continue;
            }
            if (board.equals(WEEKLY_FOCUS)) {
                weekly = gain;
            } else {
                others.add(new BoardGain(
                        board.type(), board.period(), board.slot(), gain.before(), gain.after(), gain.delta()));
            }
        }
        return new RankingSessionGainsResponse(weekly, others);
    }

    /** 명세 칩 순서 — 순공 일·주·월, 집중률 주·월, 시간대 일·주(이번 제출이 지난 구간만), 누적 시간·누적 일수·연속 일수. */
    static List<RankingBoard> targets(Set<TimeSlot> slots) {
        List<RankingBoard> boards = new ArrayList<>();
        for (RankingPeriod period : RankingPeriod.values()) {
            boards.add(new RankingBoard(RankingBoardType.FOCUS_TIME, period, null));
        }
        boards.add(new RankingBoard(RankingBoardType.FOCUS_RATE, RankingPeriod.WEEKLY, null));
        boards.add(new RankingBoard(RankingBoardType.FOCUS_RATE, RankingPeriod.MONTHLY, null));
        for (RankingPeriod period : List.of(RankingPeriod.DAILY, RankingPeriod.WEEKLY)) {
            for (TimeSlot slot : TimeSlot.values()) {
                if (slots.contains(slot)) {
                    boards.add(new RankingBoard(RankingBoardType.TIME_SLOT, period, slot));
                }
            }
        }
        boards.add(new RankingBoard(RankingBoardType.TOTAL_TIME, null, null));
        boards.add(new RankingBoard(RankingBoardType.TOTAL_DAYS, null, null));
        boards.add(new RankingBoard(RankingBoardType.MAX_STREAK, null, null));
        return boards;
    }

    /** 지금 순위가 있고, 이번 제출을 뺀 값으로도 순위가 있으며, 지금이 더 높을 때만. */
    private Gain gain(RankingBoard board, long userId, Instant submissionStartedAt, Instant now) {
        Window window = RankingCalendar.window(board, now, 0);
        BoardView view = provider.view(board, window, userId, now);
        if (!view.placement().present()) {
            return null;
        }
        Instant asOf = view.live().asOf();
        RankingEntry before = calculator
                .compute(board, window, asOf, view.live().pieces(), userId, submissionStartedAt)
                .stream()
                .findFirst()
                .orElse(null);
        if (before == null) {
            return null;
        }
        int beforeRank = view.standings().place(userId, before.advancedTo(asOf, now)).myRank();
        int afterRank = view.placement().myRank();
        return afterRank < beforeRank ? new Gain(beforeRank, afterRank, beforeRank - afterRank) : null;
    }
}
```

- [ ] **Step 5: 컨트롤러**

`RankingController.java` — 필드에 `private final RankingSessionGainsService rankingSessionGainsService;`를, import에
`project.study.ranking.dto.RankingSessionGainsResponse`, `project.study.ranking.service.RankingSessionGainsService`를 더하고 `home` 뒤에 추가:

```java
    @Operation(summary = "세션 뒤 오른 랭킹", description = """
                    방금 제출한 세션으로 순위가 오른 판을 준다(공부 결과 화면 "랭킹이 올랐어요").

                    - before: 이번 제출의 조각을 뺀 내 값, after: 지금 내 값 — 둘 다 지금의 같은 다른 사람들 사이에서 매긴 순위
                    - 대상: 지금 기간의 순공 일·주·월, 집중률 주·월, 이 세션이 지난 시간대 일·주, 누적 시간·누적 일수·연속 일수
                    - 이번 세션 전에 순위가 없던 판(처음 순위가 생긴 판)과 오르지 않은 판은 빠진다
                    - startedAt은 제출한 세션의 시작 시각(자정 분할 조각 전부를 묶는 제출 시작). 없거나 내 세션이 아니면 404""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "그 시각에 시작한 내 세션이 없음")
    @GetMapping(value = "/session-gains", version = "1")
    public RankingSessionGainsResponse sessionGains(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "제출한 세션의 시작 시각(UTC ISO-8601)", example = "2026-10-10T00:00:00Z")
                    @RequestParam
                    Instant startedAt) {
        return rankingSessionGainsService.gains(userId, startedAt);
    }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 7: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/dto/RankingSessionGainsResponse.java \
        src/main/java/project/study/ranking/service/RankingSessionGainsService.java \
        src/main/java/project/study/ranking/controller/RankingController.java \
        src/test/java/project/study/ranking/RankingSessionGainsApiTest.java
git commit -m "feat: 세션 뒤 오른 랭킹 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: spec 보충·전체 검증·리뷰·PR

**Files:**
- Modify: `docs/superpowers/specs/2026-10-10-by828-ranking-design.md` (§7.3만)

- [ ] **Step 1: spec §7.3 보충**

§7.3의 `home` 항목 하위 불릿 끝(“FE 몫” 줄 앞)에 추가:

```markdown
  - 조각 단위로 되돌린다: since까지 끝난 조각은 전부, since 뒤에 시작한 조각은 0, 걸친 조각은 `floor(순공 × 걸친 앞부분 ÷ 조각 길이)`.
    1분 기준은 조각 전체에 건다. since 시점의 도달 시각은 그때까지 쌓인 마지막 순간이다.
  - `studiedFrom`은 since 뒤에 끝나는 조각 중 가장 이른 `max(시작, since)`다 — since에 공부 중이었으면 since. `studiedFocusSec`은 지금 값 − since 값.
  - since에 내 순위가 없었으면(그때 이번 주 기록이 없었으면) `overtaken`은 null이다.
```

`session-gains` 항목 하위 불릿 끝에 추가:

```markdown
  - `before`는 같은 엔진으로 이 제출의 확정 조각을 빼고 다시 계산한 값이다(명예의 전당 포함). 진행 중인 다른 세션은 전·후 모두 그대로 둔다.
```

- [ ] **Step 2: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add docs/superpowers/specs/2026-10-10-by828-ranking-design.md
git commit -m "docs: 노출용 API의 지난 시점 복원·제출 제외 규칙을 spec에 적는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 3: 기동 확인**

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
# 다른 터미널
curl -s localhost:8080/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' -H 'API-Version: 1' 'localhost:8080/api/rankings/home'
```
Expected: `{"status":"UP"}`, 토큰 없는 `/home`은 401.

- [ ] **Step 4: 2차 리뷰 (CLAUDE.md 크로스 코드체크)**

```bash
codex exec -s read-only "git diff origin/dev...HEAD 를 리뷰해줘(첫 커밋은 계획 문서). BY-828 랭킹 노출용 API(PR ③: home·session-gains)이고 설계는 docs/superpowers/specs/2026-10-10-by828-ranking-design.md §7.3, 엔진 ADR은 docs/adr/0028-ranking-aggregate-on-read.md다. since 시점 복원(걸친 조각 비율·진행 중 draft·1분 기준·도달 시각), 추월 판정(미참가 포함·nearest 순서), 제출 제외 계산(명예의 전당 포함), 엔진을 트랜잭션 밖에서 부르는지, 다른 사용자 userId 노출을 중점으로 P1/P2/P3로 분류하고 파일:줄과 실패 시나리오를 붙여줘."
```
Expected: P1이 없을 것. P1이 있으면 고치고 Step 2부터 다시.

- [ ] **Step 5: 퀴즈 게이트 (CLAUDE.md 7번)**

사용자에게 구현 코드·흐름 퀴즈 5개를 낸다. 사용자가 생략을 명시하면 건너뛴다.

- [ ] **Step 6: push·PR**

PR 본문은 `.github/pull_request_template.md`의 절 구조를 그대로 두고 절마다 짧게 채워 스크래치패드에 쓴다. attribution 푸터는 넣지 않는다(사용자 규칙).
PR ②(#85)와 같은 파일(`RankingSource`·`ActiveStudySessionService`·spec)을 건드리므로, 먼저 머지되는 쪽 뒤에 rebase가 필요할 수 있다고 본문에 적는다.

```bash
gh auth status   # 활성 계정 sangjaekwon 확인
git push -u origin feature/BY-819-ranking-exposure
gh pr create --base dev --title "[feat] BY-828 랭킹 홈 카드·추월과 세션 뒤 오른 랭킹 API" --body-file <스크래치패드>/pr-body-pr3.md
```
지라 BY-828은 세 PR이 모두 머지되면 완료로 전환한다.
