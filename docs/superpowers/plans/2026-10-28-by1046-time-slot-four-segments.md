# 시간대 4구간과 00시 단일 마감 구현 계획 (BY-1046)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 시간대 랭킹을 KST 0·6·12·18시 경계의 4구간으로 바꾸고 모든 기간 판을 00시에 마감한다.

**Architecture:** `TimeSlot`이 구간 판정·귀속 날짜·경계의 단일 출처라 여기를 바꾸면 분할(`SlotSplitter`)·실시간 조각·
`canStillAccrue`가 따라온다. 심야 예외(04시 마감, 04시 보류)를 쓰던 `RankingCalendar`·`RankingRecordService`에서 분기를 지우고,
`DAWN`은 지난 `board_key`를 읽으려고 enum에만 남긴다. 저장된 구간 행은 V30이 비우고 기존 기동 백필이 다시 채운다.

**Tech Stack:** Java 25, Spring Boot 4.1, PostgreSQL, Flyway, JUnit 5 + AssertJ, Testcontainers

설계: `docs/superpowers/specs/2026-10-28-by1046-time-slot-four-segments-design.md`

## Global Constraints

- 구간: 0–6 `NIGHT`, 6–12 `MORNING`, 12–18 `AFTERNOON`, 18–24 `EVENING`(KST, [시작, 끝) 반개구간).
- `DAWN`은 enum에 남기되 `at()`이 돌려주지 않고, 판 생성(`RankingBoard.of`)은 400, 마감 대상(`closable`)에서 뺀다.
- enum 선언 순서는 `NIGHT, MORNING, AFTERNOON, EVENING, DAWN`(하루 순서, 기록 전용은 끝). `@Enumerated(STRING)`이라 순서를 바꿔도 저장값은 그대로다.
- 모든 기간 판은 KST 00시에 마감한다.
- 테스트를 지우거나 `@Disabled`로 피하지 않는다. 기대값을 새 경계로 고친다.
- 커밋은 `./gradlew check` 통과 상태에서만, 사용자 승인 뒤에 한다(퀴즈 5문항 게이트, 2차 리뷰 뒤).
- 이 계획의 테스트 명령은 워크트리 루트에서 JDK 25 경로를 넘겨 돌린다:
  `./gradlew test --tests "<패턴>" -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`
  (아래에서는 `-P...` 부분을 `$JDK`로 줄여 쓴다.)

---

### Task 1: 구간 정의를 4구간으로 바꾼다

**Files:**
- Modify: `src/main/java/project/study/studysession/entity/TimeSlot.java`
- Modify: `src/main/java/project/study/studysession/service/SlotSplitter.java` (클래스 주석)
- Test: `src/test/java/project/study/studysession/entity/TimeSlotTest.java`
- Test: `src/test/java/project/study/studysession/service/SlotSplitterTest.java`
- Test: `src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java:139-150`

**Interfaces:**
- Produces: `TimeSlot.LIVE` (`List<TimeSlot>`, `NIGHT, MORNING, AFTERNOON, EVENING`), `TimeSlot.at(Instant)`, `TimeSlot.slotDateOf(Instant)`(KST 달력 날짜), `TimeSlot.nextBoundary(Instant)`

- [ ] **Step 1: 실패하는 테스트로 고친다** — `TimeSlotTest`의 세 테스트를 아래로 바꾼다.

```java
    @Test
    void 구간은_시작을_포함하고_끝을_포함하지_않는다() {
        assertThat(TimeSlot.at(at(10, 0, 0))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 5, 59))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.at(at(10, 6, 0))).isEqualTo(TimeSlot.MORNING);
        assertThat(TimeSlot.at(at(10, 12, 0))).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(TimeSlot.at(at(10, 18, 0))).isEqualTo(TimeSlot.EVENING);
        assertThat(TimeSlot.at(at(10, 23, 59))).isEqualTo(TimeSlot.EVENING);
    }

    @Test
    void 귀속_날짜는_KST_달력_날짜다() {
        assertThat(TimeSlot.slotDateOf(at(11, 0, 0))).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(TimeSlot.slotDateOf(at(10, 23, 59))).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void 다음_경계는_6시간_단위이고_18시_뒤면_다음날_0시다() {
        assertThat(TimeSlot.nextBoundary(at(10, 0, 0))).isEqualTo(at(10, 6, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 7, 0))).isEqualTo(at(10, 12, 0));
        assertThat(TimeSlot.nextBoundary(at(10, 23, 0))).isEqualTo(at(11, 0, 0));
    }

    @Test
    void 마감하는_구간은_넷이고_DAWN은_어느_시각에도_나오지_않는다() {
        assertThat(TimeSlot.LIVE)
                .containsExactly(TimeSlot.NIGHT, TimeSlot.MORNING, TimeSlot.AFTERNOON, TimeSlot.EVENING);
        for (int hour = 0; hour < 24; hour++) {
            assertThat(TimeSlot.at(at(10, hour, 0))).isNotEqualTo(TimeSlot.DAWN);
        }
    }
```

`SlotSplitterTest`는 경계가 걸린 넷을 바꾼다(나머지 셋은 그대로 통과한다).

```java
    @Test
    void 경계를_넘으면_길이_비율로_나눈다() {
        assertThat(SlotSplitter.split(at(10, 5, 30), at(10, 6, 30), 3600, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(10), 1800),
                        new SessionSlot(TimeSlot.MORNING, date(10), 1800));
    }

    @Test
    void 비공부_이벤트_시간은_배분_가중치에서_빠진다() {
        // 05:30~06:30 중 06:00~06:20 PHONE → 이벤트를 뺀 길이는 심야 1800초 · 아침 600초
        List<StatusEvent> events = List.of(new StatusEvent(EventStatus.PHONE, at(10, 6, 0), at(10, 6, 20)));

        assertThat(SlotSplitter.split(at(10, 5, 30), at(10, 6, 30), 2400, events))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(10), 1800),
                        new SessionSlot(TimeSlot.MORNING, date(10), 600));
    }

    @Test
    void 배분_합은_항상_조각_순공과_같다() {
        List<SessionSlot> slots = SlotSplitter.split(at(10, 5, 0), at(10, 13, 0), 10_001, List.of());

        assertThat(slots)
                .extracting(SessionSlot::getSlot)
                .containsExactly(TimeSlot.NIGHT, TimeSlot.MORNING, TimeSlot.AFTERNOON);
        assertThat(slots.stream().mapToInt(SessionSlot::getFocusSec).sum()).isEqualTo(10_001);
    }

    @Test
    void 자정_뒤의_심야는_그날에_귀속된다() {
        assertThat(SlotSplitter.split(at(10, 2, 0), at(10, 7, 0), 18_000, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.NIGHT, date(10), 14_400),
                        new SessionSlot(TimeSlot.MORNING, date(10), 3600));
    }
```

`밤_10시_뒤의_심야는_그날에_귀속된다`는 지운다. 18–24가 한 구간이 되어 확인할 경계가 없다. 대신 아래로 바꾼다.

```java
    @Test
    void 저녁은_자정까지다() {
        assertThat(SlotSplitter.split(at(10, 17, 0), at(11, 0, 0), 25_200, List.of()))
                .containsExactly(
                        new SessionSlot(TimeSlot.AFTERNOON, date(10), 3600),
                        new SessionSlot(TimeSlot.EVENING, date(10), 21_600));
    }
```

`ActiveSessionLivePiecesTest.늘린_시간이_구간_경계를_넘으면_새_구간에_들어간다`는 시각을 한 시간 당긴다.

```java
        // 05:43:00 시작, 05:59:40 보고(순공 1000초) → 06:00:20에 40초를 늘리면 아침에 20초
        draft(userId, at(15, 5, 43, 0), at(15, 5, 59, 40), 1000, "[]");

        assertThat(mine(at(15, 6, 0, 20), userId))
                .singleElement()
                .satisfies(p -> assertThat(p.slots())
                        .containsExactlyInAnyOrder(
                                new SessionSlot(TimeSlot.NIGHT, LocalDate.of(2026, 9, 15), 1020),
                                new SessionSlot(TimeSlot.MORNING, LocalDate.of(2026, 9, 15), 20)));
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests "project.study.studysession.entity.TimeSlotTest" --tests "project.study.studysession.service.SlotSplitterTest" $JDK`
Expected: FAIL (`LIVE` 컴파일 오류 또는 기대 구간 불일치)

- [ ] **Step 3: `TimeSlot`을 바꾼다**

```java
package project.study.studysession.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 시간대 랭킹의 구간 (BY-828, BY-1046). 경계는 KST 0·6·12·18시이고 구간은 [시작, 끝) 반개구간이다.
 * DAWN(옛 04–07시)은 지난 마감 기록의 board_key를 읽으려고만 남긴다 — 어떤 시각도 DAWN이 아니고 DAWN 판도 없다.
 */
public enum TimeSlot {
    NIGHT,
    MORNING,
    AFTERNOON,
    EVENING,
    DAWN;

    /** 판이 있는 구간, 하루 순서. */
    public static final List<TimeSlot> LIVE = List.of(NIGHT, MORNING, AFTERNOON, EVENING);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int[] BOUNDARY_HOURS = {0, 6, 12, 18};

    /** 시각이 속한 구간. */
    public static TimeSlot at(Instant instant) {
        return LIVE.get(instant.atZone(KST).getHour() / 6);
    }

    /** 시각이 속한 구간의 귀속 날짜 — 모든 구간이 하루 안에 있어 KST 달력 날짜다. */
    public static LocalDate slotDateOf(Instant instant) {
        return instant.atZone(KST).toLocalDate();
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
        return date.plusDays(1).atStartOfDay(KST).toInstant();
    }
}
```

`SlotSplitter` 클래스 주석의 `(KST 04·07·12·18·22시)`를 `(KST 0·6·12·18시)`로 바꾼다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests "project.study.studysession.entity.TimeSlotTest" --tests "project.study.studysession.service.SlotSplitterTest" --tests "project.study.studysession.ActiveSessionLivePiecesTest" $JDK`
Expected: PASS

---

### Task 2: 판 생성과 마감 대상을 4구간으로 좁힌다

**Files:**
- Modify: `src/main/java/project/study/ranking/RankingBoard.java`
- Modify: `src/main/java/project/study/ranking/service/RankingSessionGainsService.java:63-75`
- Test: `src/test/java/project/study/ranking/RankingBoardTest.java`
- Test: `src/test/java/project/study/ranking/service/SessionGainsTargetsTest.java`

**Interfaces:**
- Consumes: `TimeSlot.LIVE`
- Produces: `RankingBoard.closable()` 13개, `RankingBoard.of(..., TimeSlot.DAWN, ...)`는 `BadRequestException`

- [ ] **Step 1: 실패하는 테스트로 고친다** — `RankingBoardTest`에 추가하고 둘을 고친다.

```java
    @Test
    void DAWN_판은_없다() {
        assertThatThrownBy(() -> RankingBoard.of(TIME_SLOT, DAILY, TimeSlot.DAWN, 0))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 마감하는_판은_13개이고_순공_집중률_시간대_순이다() {
        assertThat(RankingBoard.closable()).hasSize(13);
        assertThat(RankingBoard.closable().subList(0, 6))
                .containsExactly(
                        new RankingBoard(FOCUS_TIME, DAILY, null),
                        new RankingBoard(FOCUS_TIME, WEEKLY, null),
                        new RankingBoard(FOCUS_TIME, MONTHLY, null),
                        new RankingBoard(FOCUS_RATE, WEEKLY, null),
                        new RankingBoard(FOCUS_RATE, MONTHLY, null),
                        new RankingBoard(TIME_SLOT, DAILY, TimeSlot.NIGHT));
        assertThat(RankingBoard.closable().getLast()).isEqualTo(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.EVENING));
    }

    @Test
    void 키에서_판을_되돌린다() {
        for (RankingBoard board : RankingBoard.closable()) {
            assertThat(RankingBoard.fromKey(board.key())).isEqualTo(board);
        }
        assertThat(RankingBoard.fromKey("TOTAL_TIME")).isEqualTo(new RankingBoard(TOTAL_TIME, null, null));
        assertThat(RankingBoard.fromKey("TIME_SLOT:DAILY:DAWN")).isEqualTo(new RankingBoard(TIME_SLOT, DAILY, TimeSlot.DAWN));
    }
```

`SessionGainsTargetsTest`의 시간대 네 줄은 하루 순서(심야 먼저)가 된다.

```java
                        new RankingBoard(TIME_SLOT, DAILY, NIGHT),
                        new RankingBoard(TIME_SLOT, DAILY, MORNING),
                        new RankingBoard(TIME_SLOT, WEEKLY, NIGHT),
                        new RankingBoard(TIME_SLOT, WEEKLY, MORNING),
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests "project.study.ranking.RankingBoardTest" --tests "project.study.ranking.service.SessionGainsTargetsTest" $JDK`
Expected: FAIL (`DAWN_판은_없다`, 판 수 15)

`SessionGainsTargetsTest`는 Task 1의 enum 순서 변경으로 이미 통과할 수 있다. 그래도 `targets`가 `LIVE`를 쓰게 바꾼다.

- [ ] **Step 3: 구현한다** — `RankingBoard.of`에서 `TIME_SLOT`/`slot` 일치 검사 바로 뒤에 넣는다.

```java
        if (slot != null && !TimeSlot.LIVE.contains(slot)) {
            throw new BadRequestException(slot + " 구간은 지난 기록에만 있습니다");
        }
```

`addSlots`의 `TimeSlot.values()`와 `RankingSessionGainsService.targets`의 `TimeSlot.values()`를 `TimeSlot.LIVE`로 바꾼다.
`closable()` 주석을 `마감을 기록하는 기간 판 13개 — 순공 일·주·월, 집중률 주·월, 시간대 일·주 × 4구간.`으로 고친다.

- [ ] **Step 4: 통과를 확인한다**

Run: 같은 명령
Expected: PASS

---

### Task 3: 모든 기간 판을 00시에 마감한다

**Files:**
- Modify: `src/main/java/project/study/ranking/RankingCalendar.java`
- Modify: `src/main/java/project/study/studysession/service/SessionSlotBackfill.java` (주석)
- Test: `src/test/java/project/study/ranking/RankingCalendarTest.java`
- Test: `src/test/java/project/study/ranking/service/CanStillAccrueTest.java`
- Test: `src/test/java/project/study/ranking/RankingCloserTest.java:90-108`
- Test: `src/test/java/project/study/studysession/service/SessionSlotBackfillTest.java:92-108`

**Interfaces:**
- Consumes: `TimeSlot.slotDateOf`(Task 1)
- Produces: `RankingCalendar.window(board, now, offset)` — 모든 기간 판의 `closesAt`이 다음 기간 시작일 KST 00시

- [ ] **Step 1: 실패하는 테스트로 고친다**

`RankingCalendarTest`의 심야 두 테스트를 하나로 바꾼다.

```java
    @Test
    void 심야판도_그날이고_다음날_0시에_마감한다() {
        RankingBoard night = new RankingBoard(TIME_SLOT, DAILY, TimeSlot.NIGHT);

        assertThat(RankingCalendar.window(night, at(10, 11, 2), 0))
                .isEqualTo(new Window(date(10, 11), date(10, 11), at(10, 12, 0)));
        assertThat(RankingCalendar.window(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.NIGHT), at(10, 12, 2), 0))
                .isEqualTo(new Window(date(10, 12), date(10, 18), at(10, 19, 0)));
    }
```

`CanStillAccrueTest.심야_일간판은_마감이_다음날_04시라_저녁에도_쌓을_수_있다`를 바꾼다.

```java
    @Test
    void 심야_일간판은_오후엔_이미_지나_더_쌓을_수_없다() {
        assertThat(canAccrue(slot(RankingPeriod.DAILY, TimeSlot.NIGHT), SAT_AFTERNOON))
                .isFalse();
    }
```

`RankingCloserTest.심야판은_다음날_04시_1분에_확정하고_그_전에는_열어_둔다`를 바꾼다.

```java
    @Test
    void 심야판도_다음날_00시_1분에_확정한다() {
        session(user("a"), kst(10, 9, 2, 0), 60, 3000); // 금요일 심야(0시~6시)

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(skipped("TIME_SLOT:DAILY:NIGHT", FRI)).isFalse();
        assertThat(jdbc.queryForObject(
                                "SELECT closes_at FROM ranking_close WHERE board_key = ? AND period_start = ?",
                                OffsetDateTime.class,
                                "TIME_SLOT:DAILY:NIGHT",
                                FRI)
                        .toInstant())
                .isEqualTo(kst(10, 10, 0, 0));
        assertThat(records()).contains("TIME_SLOT:DAILY:NIGHT a 1 3000.0");
    }
```

`SessionSlotBackfillTest`의 귀속일 세 테스트를 하나로 바꾼다.

```java
    @Test
    void 지난_주_월요일부터_채운다() {
        assertThat(SessionSlotBackfill.fromDate(kst(10, 12, 1))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(SessionSlotBackfill.fromDate(kst(10, 11, 23))).isEqualTo(LocalDate.of(2026, 9, 28));
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests "project.study.ranking.RankingCalendarTest" --tests "project.study.ranking.service.CanStillAccrueTest" --tests "project.study.ranking.RankingCloserTest" --tests "project.study.studysession.service.SessionSlotBackfillTest" $JDK`
Expected: FAIL (심야판 마감 04시). `SessionSlotBackfillTest`는 Task 1로 이미 통과할 수 있다.

- [ ] **Step 3: 구현한다** — `RankingCalendar`에서 `NIGHT_CLOSE`·`LocalTime`·`TimeSlot` import를 지우고 `window`를 바꾼다.

```java
    /** offset -1은 직전 기간. */
    public static Window window(RankingBoard board, Instant now, int offset) {
        if (board.type().hallOfFame()) {
            return new Window(ALL_TIME_START, now.atZone(KST).toLocalDate(), null);
        }
        LocalDate start = shift(periodStart(board.period(), now.atZone(KST).toLocalDate()), board.period(), offset);
        LocalDate next = shift(start, board.period(), 1);
        return new Window(start, next.minusDays(1), next.atStartOfDay(KST).toInstant());
    }
```

`SessionSlotBackfill` 클래스 주석에서 `(월요일 04시 전의 심야판 포함)`을 지우고, `fromDate` 주석을 `백필을 시작할 날짜 — 지난 주 월요일. 이번 주 판과 직전 주(offset=-1) 판이 모두 덮인다.`로 바꾼다.

- [ ] **Step 4: 통과를 확인한다**

Run: 같은 명령
Expected: PASS

---

### Task 4: 마감 모달의 04시 보류를 없앤다

**Files:**
- Modify: `src/main/java/project/study/ranking/service/RankingRecordService.java`
- Modify: `src/main/java/project/study/ranking/repository/RankingRecordQueries.java:61-69`
- Modify: `src/main/java/project/study/ranking/repository/RankingCloseRepository.java:63-70` (`countClosedAt` 삭제)
- Test: `src/test/java/project/study/ranking/RankingUnseenApiTest.java`
- Test: `src/test/java/project/study/ranking/RankingCloseRepositoryTest.java` (`마감_시각으로_세면_건너뛴_마감도_센다` 삭제)
- Test: `src/test/java/project/study/ranking/RankingRecordListApiTest.java`, `RankingRecordSummaryApiTest.java` (DAWN 기록)

**Interfaces:**
- Produces: `RankingRecordQueries.unseen(long userId)` — 상한 인자 없음

- [ ] **Step 1: 실패하는 테스트로 고친다** — `RankingUnseenApiTest`

첫 테스트는 구간 순서가 하루 순서라 심야가 아침보다 앞선다. `closed(...)` 줄은 더 필요 없어 지운다.

```java
        assertUnseen(me, daily, night, morning, weekly, second);
```

04시 보류 두 테스트(`그날_04시_심야_마감이_확정되기_전에는_00시_마감분을_주지_않는다`, `월요일엔_심야_주간_마감까지_표시돼야_00시_마감분을_주고_건너뛴_마감도_센다`)를 하나로 바꾼다.

```java
    @Test
    void 마감된_안_본_기록은_시각과_상관없이_모두_준다() {
        long me = user("me");
        long saturday = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 1, "5000.0");
        long friday = record(me, "FOCUS_TIME:DAILY", THU, kst(10, 9, 0, 0), 1, "4000.0");

        clock.set(kst(10, 10, 0, 2));
        assertUnseen(me, friday, saturday);
    }
```

나머지 테스트의 `closed("TIME_SLOT:DAILY:NIGHT", ...)` 준비 줄을 지운다. `insertDailyRecords` 주석의 `모두 오늘 04시 보류 상한 안에서 마감한다.`를 지운다.

`RankingRecordListApiTest`에 지난 `DAWN` 기록 테스트를 더한다.

```java
    @Test
    void 지난_새벽_구간_기록도_그대로_준다() {
        long me = user("me");
        record(me, "TIME_SLOT:DAILY:DAWN", LocalDate.of(2026, 10, 8), kst(10, 9, 0, 0), 1, "3000.0");

        assertThat(get("/api/rankings/records", me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.records[0].slot", v -> assertThat(v).isEqualTo("DAWN"));
    }
```

`RankingRecordSummaryApiTest`에 지난 `DAWN` 개인 최고 테스트를 더한다.

```java
    @Test
    void 역대_최고가_지난_새벽_구간이어도_준다() {
        long me = user("me");
        best(me, 1, "TIME_SLOT:DAILY:DAWN", LocalDate.of(2026, 10, 8));

        assertThat(get(SUMMARY, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.best.slot", v -> assertThat(v).isEqualTo("DAWN"));
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew test --tests "project.study.ranking.RankingUnseenApiTest" --tests "project.study.ranking.RankingRecordListApiTest" --tests "project.study.ranking.RankingRecordSummaryApiTest" $JDK`
Expected: `마감된_안_본_기록은_시각과_상관없이_모두_준다` FAIL(00:02엔 하루 전 04시까지만 준다). DAWN 두 테스트는 enum에 DAWN이 남아 있어 통과할 수 있다(회귀 방지용).

- [ ] **Step 3: 구현한다**

`RankingRecordQueries.unseen`:

```java
    public List<RankingRecordRow> unseen(long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM ranking_record WHERE user_id = :userId AND seen_at IS NULL")
                .param("userId", userId)
                .query(ROW)
                .list();
    }
```

`RankingRecordService`: `DAILY_NIGHT_BOARD`·`DAILY_NIGHT`·`WEEKLY_NIGHT`·`closes` 필드·`unseenCutoff`를 지우고 쓰지 않게 된 import
(`DayOfWeek`, `Instant`, `LocalDate`, `RankingCalendar`, `RankingPeriod`, `RankingCloseRepository`, `TimeSlot`)를 정리한다. `unseen`은 아래가 된다.

```java
    /** 마감 모달 — 안 본 기록을 모달 순서로. 한 번에 최대 100개(seen이 받는 수와 같다) — 나머지는 seen으로 표시한 뒤 다음 호출에 준다. */
    @Transactional(readOnly = true)
    public RankingUnseenResponse unseen(long userId) {
        List<RankingRecordItem> records = queries.unseen(userId).stream()
                .map(RecordItems::of)
                .sorted(MODAL_ORDER)
                .limit(RankingRecordSeenRequest.MAX_IDS)
                .toList();
        return new RankingUnseenResponse(queries.counts(userId).total(), records);
    }
```

클래스 주석은 `랭킹 마감 기록 조회 (BY-828) — 모두 보기, 마감 모달, 본 것으로 표시.`로 바꾼다. 다른 곳에서 쓰지 않는 `RankingCloseRepository.countClosedAt`과 그 테스트를 지운다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew test --tests "project.study.ranking.*" $JDK`
Expected: PASS

---

### Task 5: 저장된 구간 행을 비우고 문서를 고친다

**Files:**
- Create: `src/main/resources/db/migration/V30__reset_session_slot_for_four_segments.sql`
- Create: `docs/adr/0033-ranking-time-slot-four-segments.md`
- Modify: `docs/adr/0029-ranking-close-records.md` (결정 6 표시)
- Modify: `src/main/java/project/study/ranking/controller/RankingController.java:37,53`
- Modify: `src/main/java/project/study/ranking/controller/RankingRecordController.java:68-69`
- Modify: `src/main/java/project/study/ranking/RankingPeriod.java:3`
- Modify: `src/main/java/project/study/ranking/dto/RecordCursor.java:11`
- Test: `src/test/java/project/study/studysession/service/SessionSlotBackfillTest.java`

- [ ] **Step 1: 백필이 새 경계로 채우는지 테스트를 더한다**

`SessionSlotBackfillTest`의 `legacySession(LocalDate)`은 그날 10시 세션을 만든다. 경계를 걸치는 세션을 직접 넣는 헬퍼가 없으면 `legacySession`과 같은 INSERT로 05:30~06:30 세션을 만들고 아래를 확인한다.

```java
    @Test
    void 구간_행이_없는_세션은_새_경계로_나눠_채운다() {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        long id = legacySession(today.atTime(5, 30).atZone(KST).toInstant(), today.atTime(6, 30).atZone(KST).toInstant(), 3600);

        backfill.run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForList(
                        "SELECT slot FROM study_session_slot WHERE session_id = ? ORDER BY slot", String.class, id))
                .containsExactly("MORNING", "NIGHT");
    }
```

`legacySession`이 날짜만 받으면 시작·끝·순공을 받는 오버로드를 더하고 기존 메서드가 그것을 부르게 한다.

- [ ] **Step 2: 마이그레이션을 쓴다** — `V30__reset_session_slot_for_four_segments.sql`

```sql
-- BY-1046: 시간대 구간을 4구간(KST 0·6·12·18시)으로 바꾼다 — 옛 5구간 행을 비우면 기동 백필(SessionSlotBackfill)이
-- 지난 주 월요일부터의 측정 세션을 새 경계로 다시 채운다. 배분은 이벤트 비율이라 SQL로 다시 계산하지 않는다(ADR-0033)
DELETE FROM study_session_slot;
```

- [ ] **Step 3: ADR-0033을 쓴다** — 설계 문서 §2 결정 1~5를 ADR 형식(상태·날짜·티켓·설계·관련 / 맥락 / 결정 / 결과)으로 옮긴다. 결과에는 V30, 마감 판 13개, `countClosedAt` 삭제, `slot=DAWN` 400, 마감 모달 순서가 하루 순서(심야 먼저)가 된 것을 적는다.

- [ ] **Step 4: ADR-0029 결정 6 첫 줄 끝에 `(ADR-0033으로 대체 — 04시 보류 없음)`을 붙이고, 결정 3의 `04시 보류(결정 6)가 영영 막히지 않게`를 지운다.**

- [ ] **Step 5: 문구를 고친다**
  - `RankingController` 37행: `- 기간은 KST — 일간 00시, 주간 월요일 00시, 월간 1일 00시 마감`
  - `RankingController` 53행 slot 설명: `"시간대 구간 — 시간대 판만, 생략하면 지금 구간. NIGHT 0–6·MORNING 6–12·AFTERNOON 12–18·EVENING 18–24시(KST). DAWN은 지난 기록에만 있어 400"`
  - `RankingRecordController` 68–69행의 04시 보류 항목을 지운다.
  - `RankingPeriod` 주석에서 `(심야판만 04시)`를 지운다.
  - `RecordCursor` 주석의 `정각(00시·04시)`을 `정각(00시)`으로 바꾼다. 지난 기록에 04시 마감이 남아 있어도 초 단위라 그대로 담긴다.

- [ ] **Step 6: 전체 검증**

Run: `./gradlew spotlessApply $JDK && ./gradlew check $JDK`
Expected: BUILD SUCCESSFUL. 실패하는 테스트가 있으면 04시·옛 경계를 기대하는 곳인지 확인하고 새 경계로 고친다.

---

### 마무리 (계획 밖 게이트)

- 독립 2차 리뷰(Fable 5.1 high, codex 대체)에서 P1이 없어야 한다.
- 퀴즈 5문항을 통과한 뒤 커밋 계획을 제시하고 승인받는다. 커밋 묶음 제안: Task 1~4 `feat: 시간대 랭킹을 4구간과 00시 마감으로 바꾼다`, Task 5 마이그레이션 `feat: 옛 구간 행을 비우고 다시 채운다`, ADR·문구 `docs: 시간대 4구간 ADR을 추가한다`.
- 푸시 전에 BY-1047(FE)을 이 브랜치로 띄운 로컬 백엔드에 붙여 확인한다.
