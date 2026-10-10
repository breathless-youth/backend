# 랭킹 마감 배치와 랭킹 기록 API (BY-828 PR ②) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기간 판이 마감되면 1분 뒤 최종 순위를 한 번 확정해 TOP 3 메달과 참가자의 역대 최고 순위를 남기고, 메달 시트·모두 보기·마감 모달용 기록 API 4개를 내려준다.

**Architecture:** 매분 도는 스케줄러가 판 15개마다 "직전 기간이 마감 + 1분을 지났고 마감 표시가 없는가"를 보고, PR ①의 엔진
(`StandingsCalculator.compute`, 자체 `REPEATABLE_READ` 트랜잭션)으로 쓰기 트랜잭션 밖에서 최종 순위표를 계산한 뒤, 마감 표시·메달·개인
최고를 한 트랜잭션에 쓴다. 마감 표시의 PK가 중복 확정을 막고, 1시간 넘게 늦은 판은 건너뛴 것으로만 표시한다. 기록 API는 `ranking_record`를
읽고, 마감 모달은 그날 04시 심야 마감이 확정될 때까지 00시 마감분을 보류한다. 요약의 "메달에 가장 가까운 판"은 엔진 캐시(`StandingsProvider`)로 계산한다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL 17, Flyway, `JdbcClient`/`JdbcTemplate`, Jackson 3(`tools.jackson`), Sentry,
JUnit 5 + AssertJ, `MockMvcTester`, Testcontainers 2.

**Spec:** `docs/superpowers/specs/2026-10-10-by828-ranking-design.md` (§2 결정 3·4·5·7, §5.2 V27, §7.2 기록 API, §8 마감 배치, §10 오류·경계, §11 테스트).
PR ①의 엔진·결정은 `docs/adr/0028-ranking-aggregate-on-read.md`.

## Global Constraints

- 새 의존성 없음. `SecurityConfig` 변경 없음(`/api/rankings/**`는 기본 인증 `anyRequest`에 걸린다).
- 새 엔드포인트는 `version = "1"`. 테스트는 `.header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)`를 `.with(asUser(...))`보다 먼저 싣는다 — `asUser`는 기본으로 2를 싣는다.
- **`StandingsCalculator.compute`·`rateTotals`·`StandingsProvider.view`를 부르는 코드는 트랜잭션을 걸지 않는다**(ADR-0028 결정 8 — 바깥 트랜잭션 안에서 부르면 `IllegalStateException`). 해당: `RankingCloser`, `RankingCloseScheduler`, `RankingRecordSummaryService.summary`. 쓰기는 `RankingCloseWriter.write`의 `@Transactional`에서만 한다.
- 마감 대기 1분(`SETTLE_DELAY`), 따라잡기 창 1시간(`CATCH_UP_LIMIT`). 판 목록·순서는 `RankingBoard.closable()`(순공 일·주·월, 집중률 주·월, 시간대 일 × 5구간, 시간대 주 × 5구간 = 15개).
- 메달: 1~3위 중 순공·시간대 판은 값 ≥ 1800초만, 집중률은 조건 없음. 못 받은 자리를 다음 순위로 당기지 않는다. 기록 값은 `numeric(12,1)`(시간 판 초, 집중률 % 소수 1자리 반올림).
- 개인 최고: 참가자 전원, 더 높은 순위일 때만 바꾸고 같으면 먼저 것을 둔다.
- board_key는 `RankingBoard.key()` 형식(`FOCUS_TIME:DAILY`, `TIME_SLOT:WEEKLY:NIGHT`).
- timestamptz는 JDBC에 `instant.atOffset(ZoneOffset.UTC)`로 넘기고 `rs.getObject(col, OffsetDateTime.class).toInstant()`로 읽는다.
- 기록 목록: `(closes_at, id)` 내림차순, `size` 기본 20·최대 50, `rank` 1·2·3, `type` `FOCUS_TIME`·`FOCUS_RATE`·`TIME_SLOT`. 범위 밖은 400.
- 응답 값 표기는 PR ①의 `BoardValues.value`(집중률 소수 1자리, 나머지 정수)를 그대로 쓴다.
- 기간 경계는 KST. 심야(`NIGHT`)판만 04시 마감이고 `TimeSlot.slotDateOf`(04시 전이면 전날)가 기준.
- checkstyle(테스트 포함): 파일 400줄, 메서드 60줄, 순환 복잡도 10, 파라미터 7개 이하, 안 쓰는 import 금지. 포맷은 `./gradlew spotlessApply`.
- CLAUDE.md: DTO는 record, 생성자 주입만, `@Data`·`@Profile` 금지, 기능 토글은 `@ConditionalOnProperty` + `app.<기능>.enabled`, 커밋은 `./gradlew check` 통과 상태에서만.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 퀴즈 게이트(CLAUDE.md 7번)는 Task 8에서 push·PR 전에 한 번 한다. 사용자가 생략을 명시하면 건너뛴다.

## 설계 보충 (spec에 없어 이 계획이 정한 것)

| 항목 | 정한 것 | 이유 |
|---|---|---|
| 중복 확정 | 마감 표시를 `INSERT … ON CONFLICT DO NOTHING`, 영향 0행이면 쓰지 않음 | spec의 "PK 충돌이면 롤백"과 같은 결과를 예외 없이 낸다 |
| 마감용 draft 조각 | `livePieces(asOf, false)` — Δ 연장 없음, `focusing` 끔 | spec §8 "집중 중 보정 없음". 앱 시계가 늦으면 연장분이 마감 전으로 새어 든다 |
| 스케줄 | `cron = "5 * * * * *"`(KST), 매분 5초 | 00:01:05에 00시 마감분이 확정된다. fixedDelay는 밀린다 |
| 스케줄러 토글 | `app.ranking.close.enabled`(없으면 켜짐), 테스트 yaml에서 끔 | 기존 `app.room.cleanup.enabled`와 같은 방식 |
| 커서 | base64url(`"<마감 시각 epoch 초>:<id>"`), 깨지면 400 | 마감 시각은 정각이라 초로 충분, FE엔 불투명 |
| seen 요청 | `ids` 필수·최대 100개, `[]`는 204 no-op | 무제한 IN 절 방지 |
| `closest` 동점 | `closable()` 순서상 앞 판 | 결정적 결과 |
| 모달 순서 동점 | 순위 → 일·주·월 → 순공·집중률·시간대 → 구간 → 마감 시각 오름차순 → id | spec 순서 뒤에 결정적 꼬리 |
| ADR | ADR-0029 새로 작성 | 마감 확정 규칙은 ADR-0028(조회 집계)과 별개 결정 |

## Review Focus

1. **첫 배포·긴 재기동** — 마감 뒤 1시간이 넘은 판은 메달 없이 건너뛴 것으로 표시되고, 건너뛴 심야 마감도 04시 보류를 풀어야 한다. → Task 4(건너뜀)·Task 6(건너뛴 심야 주간 마감으로 보류 해제) 테스트.
2. **배포 중 태스크 둘이 같은 판을 확정** — 두 번째 쓰기는 마감 표시·메달·개인 최고 어느 것도 남기지 않아야 한다. → Task 3 테스트.
3. **앱 시계가 서버보다 늦은 draft** — 보고 시각은 자정 전, 서버 수신은 자정 뒤라 "집중 중"이어도 마감 판엔 보고된 값만 들어가야 한다. → Task 4 테스트.
4. **남의 기록 id를 seen으로 보냄** — 내 기록만 바뀌고 남의 기록은 그대로여야 한다. → Task 6 테스트.
5. **같은 마감 시각의 기록이 페이지 경계에 걸림** — 커서로 넘겨도 빠지거나 겹치지 않아야 한다. → Task 5 테스트.

---

## 실행 전 준비

- [ ] 브랜치 확인: `git branch --show-current` → `feature/BY-819-ranking-records`, `git log --oneline -1` → `d56ceb5`(PR #84 머지 커밋) 또는 그 뒤. 아니면 `git fetch origin && git checkout -b feature/BY-819-ranking-records origin/dev`.
- [ ] 마이그레이션 번호 확인: `ls src/main/resources/db/migration | sort -V | tail -1` → `V26__study_session_slot.sql`. dev에 V27이 새로 생겼으면 이 계획의 V27을 다음 번호로 바꾼다.
- [ ] 지라 BY-828은 이미 진행 중이다(전환 불필요).

## 파일 구조

| 파일 | 책임 |
|---|---|
| `resources/db/migration/V27__ranking_record.sql` | 마감 표시·메달·개인 최고 테이블 |
| `ranking/RankingBoard.java` (수정) | 마감 판 목록 `closable()`, `fromKey` |
| `ranking/repository/RankingCloseRepository.java` | 마감 쓰기(마감 표시·메달·개인 최고) + 마감 표시 조회 |
| `studysession/service/ActiveStudySessionService.java` (수정) | `livePieces(asOf, extendFocusing)` |
| `studysession/service/RankingSource.java` (수정) | `settledPieces(asOf)` |
| `ranking/close/Medals.java` | 메달 규칙(30분·안 당김)·기록 값 |
| `ranking/close/RankingCloseWriter.java` | 판 하나의 마감을 한 트랜잭션에 쓴다 |
| `ranking/close/RankingCloser.java` | 마감할 판을 찾아 계산·쓰기·건너뜀 |
| `ranking/scheduler/RankingCloseScheduler.java` | 매분 마감 실행 |
| `ranking/dto/RecordCursor.java` | 목록 커서 인코딩 |
| `ranking/dto/RankingRecordRow·RankingBestRow·MedalCounts.java` | 기록 조회 행 |
| `ranking/dto/RankingRecordItem·RankingRecordPageResponse·RankingUnseenResponse·RankingRecordSummaryResponse·RankingRecordSeenRequest.java` | API 계약 |
| `ranking/repository/RankingRecordQueries.java` | 기록 읽기·seen 표시 |
| `ranking/service/RecordItems.java` | 기록 행 → 응답 항목 |
| `ranking/service/RankingRecordService.java` | 목록·안 본 기록(04시 보류)·seen |
| `ranking/service/RankingRecordSummaryService.java` | 요약(개수·최근·역대 최고·가장 가까운 판) |
| `ranking/controller/RankingRecordController.java` | `/api/rankings/records/**` 4개 |
| `docs/adr/0029-ranking-close-records.md` | 마감 확정 결정 |

---

### Task 1: V27 마이그레이션 · 마감 판 목록 · 마감 저장소

**Files:**
- Create: `src/main/resources/db/migration/V27__ranking_record.sql`
- Modify: `src/main/java/project/study/ranking/RankingBoard.java`
- Create: `src/main/java/project/study/ranking/repository/RankingCloseRepository.java`
- Modify: `src/test/java/project/study/ranking/RankingIntegrationTestBase.java` (TRUNCATE 목록)
- Modify: `src/test/java/project/study/ranking/RankingBoardTest.java`
- Create: `src/test/java/project/study/ranking/RankingCloseRepositoryTest.java`

**Interfaces:**
- Consumes: `RankingBoard.key()`, `RankingBoardType.supports(RankingPeriod)`, `TimeSlot.values()` (PR ①)
- Produces:
  - `static List<RankingBoard> RankingBoard.closable()` — 15개, 순서 고정
  - `static RankingBoard RankingBoard.fromKey(String key)`
  - `RankingCloseRepository`:
    - `boolean insertClose(String boardKey, LocalDate periodStart, Instant closesAt, Instant closedAt, boolean skipped)` — 이미 있으면 false
    - `boolean isClosed(String boardKey, LocalDate periodStart)`
    - `int countClosedAt(Collection<String> boardKeys, Instant closesAt)`
    - `void insertRecord(long userId, String boardKey, LocalDate periodStart, Instant closesAt, int rank, BigDecimal value)`
    - `void upsertBest(String boardKey, LocalDate periodStart, List<Long> userIdsInRankOrder)`

- [ ] **Step 1: 실패하는 테스트 작성 — 판 목록**

`src/test/java/project/study/ranking/RankingBoardTest.java` 클래스 끝에 추가:

```java
    @Test
    void 마감하는_판은_15개이고_순공_집중률_시간대_순이다() {
        assertThat(RankingBoard.closable()).hasSize(15);
        assertThat(RankingBoard.closable().subList(0, 6))
                .containsExactly(
                        new RankingBoard(FOCUS_TIME, DAILY, null),
                        new RankingBoard(FOCUS_TIME, WEEKLY, null),
                        new RankingBoard(FOCUS_TIME, MONTHLY, null),
                        new RankingBoard(FOCUS_RATE, WEEKLY, null),
                        new RankingBoard(FOCUS_RATE, MONTHLY, null),
                        new RankingBoard(TIME_SLOT, DAILY, TimeSlot.DAWN));
        assertThat(RankingBoard.closable().getLast()).isEqualTo(new RankingBoard(TIME_SLOT, WEEKLY, TimeSlot.NIGHT));
    }

    @Test
    void 키에서_판을_되돌린다() {
        for (RankingBoard board : RankingBoard.closable()) {
            assertThat(RankingBoard.fromKey(board.key())).isEqualTo(board);
        }
        assertThat(RankingBoard.fromKey("TOTAL_TIME")).isEqualTo(new RankingBoard(TOTAL_TIME, null, null));
    }
```

- [ ] **Step 2: 실패하는 테스트 작성 — 마감 저장소**

`src/test/java/project/study/ranking/RankingCloseRepositoryTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import project.study.ranking.repository.RankingCloseRepository;

class RankingCloseRepositoryTest extends RankingIntegrationTestBase {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Autowired
    private RankingCloseRepository repository;

    @Test
    void 같은_판_기간의_마감은_한_번만_표시한다() {
        Instant closesAt = kst(10, 10, 0, 0);

        assertThat(repository.insertClose("FOCUS_TIME:DAILY", FRI, closesAt, closesAt.plusSeconds(65), false))
                .isTrue();
        assertThat(repository.insertClose("FOCUS_TIME:DAILY", FRI, closesAt, closesAt.plusSeconds(125), false))
                .isFalse();
        assertThat(repository.isClosed("FOCUS_TIME:DAILY", FRI)).isTrue();
        assertThat(repository.isClosed("FOCUS_TIME:WEEKLY", FRI)).isFalse();
    }

    @Test
    void 마감_시각으로_세면_건너뛴_마감도_센다() {
        Instant mondayNight = kst(10, 12, 4, 0);
        repository.insertClose(
                "TIME_SLOT:DAILY:NIGHT", LocalDate.of(2026, 10, 11), mondayNight, mondayNight.plusSeconds(65), false);
        repository.insertClose(
                "TIME_SLOT:WEEKLY:NIGHT", LocalDate.of(2026, 10, 5), mondayNight, mondayNight.plusSeconds(7200), true);

        assertThat(repository.countClosedAt(List.of("TIME_SLOT:DAILY:NIGHT", "TIME_SLOT:WEEKLY:NIGHT"), mondayNight))
                .isEqualTo(2);
        assertThat(repository.countClosedAt(List.of("TIME_SLOT:DAILY:NIGHT"), mondayNight.minusSeconds(86_400)))
                .isZero();
    }

    @Test
    void 같은_판_기간의_같은_순위는_하나만_기록한다() {
        long a = user("a");
        long b = user("b");
        Instant closesAt = kst(10, 10, 0, 0);
        repository.insertRecord(a, "FOCUS_TIME:DAILY", FRI, closesAt, 1, new BigDecimal("3000.0"));

        assertThatThrownBy(() ->
                        repository.insertRecord(b, "FOCUS_TIME:DAILY", FRI, closesAt, 1, new BigDecimal("2000.0")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 개인_최고는_더_높은_순위일_때만_바꾸고_같으면_먼저_것을_둔다() {
        long a = user("a");
        long b = user("b");
        long c = user("c");

        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 6), List.of(a, b));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 7), List.of(b, a));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 8), List.of(c, b, a));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 9), List.of(a));
        repository.upsertBest("FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 10), List.of());

        assertThat(best(a)).isEqualTo("1 FOCUS_TIME:DAILY 2026-10-06");
        assertThat(best(b)).isEqualTo("1 FOCUS_TIME:DAILY 2026-10-07");
        assertThat(best(c)).isEqualTo("1 FOCUS_TIME:DAILY 2026-10-08");
    }

    private String best(long userId) {
        return jdbc.queryForObject(
                "SELECT rank || ' ' || board_key || ' ' || period_start FROM ranking_best WHERE user_id = ?",
                String.class,
                userId);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingBoardTest" --tests "project.study.ranking.RankingCloseRepositoryTest"`
Expected: 컴파일 실패 — `closable`·`fromKey`·`RankingCloseRepository`가 없다.

- [ ] **Step 4: 마이그레이션 작성**

`src/main/resources/db/migration/V27__ranking_record.sql`:

```sql
-- BY-828: 랭킹 마감 기록 (ADR-0029) — 판·기간마다 마감 1분 뒤 한 번 확정하고 이후 바꾸지 않는다.
-- 마감 표시: 판·기간당 1행. PK가 태스크 둘의 중복 확정을 막는다. skipped = 마감 뒤 1시간이 넘어 확정하지 않고 건너뜀
CREATE TABLE ranking_close (
    board_key    VARCHAR     NOT NULL,   -- 예: FOCUS_TIME:WEEKLY, TIME_SLOT:DAILY:NIGHT
    period_start DATE        NOT NULL,
    closes_at    TIMESTAMPTZ NOT NULL,   -- 기간 마감 시각
    closed_at    TIMESTAMPTZ NOT NULL,   -- 확정한 시각
    skipped      BOOLEAN     NOT NULL,
    PRIMARY KEY (board_key, period_start)
);

-- 메달: 1·2·3위 중 메달 조건(시간 판 30분)을 넘은 사람만. 탈퇴해도 남지만 본인만 조회한다
CREATE TABLE ranking_record (
    id           BIGSERIAL      PRIMARY KEY,
    user_id      BIGINT         NOT NULL,
    board_key    VARCHAR        NOT NULL,
    period_start DATE           NOT NULL,
    closes_at    TIMESTAMPTZ    NOT NULL,
    rank         SMALLINT       NOT NULL,   -- 1·2·3
    value        NUMERIC(12, 1) NOT NULL,   -- 초 또는 집중률(%)
    seen_at      TIMESTAMPTZ,               -- 마감 모달을 본 시각
    UNIQUE (board_key, period_start, rank)
);
CREATE INDEX idx_ranking_record_user ON ranking_record (user_id, closes_at DESC);

-- 개인 최고: 역대 마감 최종 순위 중 최고, 사용자당 1행. 같은 순위면 먼저 것을 둔다
CREATE TABLE ranking_best (
    user_id      BIGINT  PRIMARY KEY,
    rank         INT     NOT NULL,
    board_key    VARCHAR NOT NULL,
    period_start DATE    NOT NULL
);
```

- [ ] **Step 5: 테스트 기반이 새 테이블도 비우게 수정**

`src/test/java/project/study/ranking/RankingIntegrationTestBase.java`의 `resetRankingState`에서 TRUNCATE 줄을 바꾼다
(마감 테이블은 `users`에 FK가 없어 CASCADE로 지워지지 않는다):

```java
        jdbc.execute("TRUNCATE users, active_study_session, ranking_close, ranking_record, ranking_best "
                + "RESTART IDENTITY CASCADE");
```

- [ ] **Step 6: 판 목록·키 되돌리기 구현**

`src/main/java/project/study/ranking/RankingBoard.java` — import에 `java.util.ArrayList`, `java.util.List`를 추가하고, 레코드 본문 맨 앞에
상수를, `of(...)` 뒤에 두 공개 메서드를, `key()` 뒤에 두 private 메서드를 추가한다:

```java
    private static final List<RankingBoard> CLOSABLE = buildClosable();
```

```java
    /**
     * 마감을 기록하는 기간 판 15개 — 순공 일·주·월, 집중률 주·월, 시간대 일·주 × 5구간. 마감 배치와 기록 요약이 이 순서로 돈다.
     */
    public static List<RankingBoard> closable() {
        return CLOSABLE;
    }

    /** key()의 역 — 마감 기록의 board_key를 판으로 되돌린다. */
    public static RankingBoard fromKey(String key) {
        String[] parts = key.split(":");
        return new RankingBoard(
                RankingBoardType.valueOf(parts[0]),
                parts.length > 1 ? RankingPeriod.valueOf(parts[1]) : null,
                parts.length > 2 ? TimeSlot.valueOf(parts[2]) : null);
    }
```

```java
    private static List<RankingBoard> buildClosable() {
        List<RankingBoard> boards = new ArrayList<>();
        for (RankingBoardType type : RankingBoardType.values()) {
            for (RankingPeriod period : RankingPeriod.values()) {
                if (type.supports(period)) {
                    addSlots(boards, type, period);
                }
            }
        }
        return List.copyOf(boards);
    }

    /** 시간대 판은 구간마다 한 판이다. */
    private static void addSlots(List<RankingBoard> boards, RankingBoardType type, RankingPeriod period) {
        if (type != RankingBoardType.TIME_SLOT) {
            boards.add(new RankingBoard(type, period, null));
            return;
        }
        for (TimeSlot slot : TimeSlot.values()) {
            boards.add(new RankingBoard(type, period, slot));
        }
    }
```

(명예의 전당 종목은 `supports`가 모든 기간에 false라 자연히 빠진다.)

- [ ] **Step 7: 마감 저장소 구현**

`src/main/java/project/study/ranking/repository/RankingCloseRepository.java`:

```java
package project.study.ranking.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 랭킹 마감 쓰기 (BY-828, ADR-0029) — 판·기간마다 한 번만 확정하는 마감 표시, TOP 3 메달, 참가자의 역대 최고 순위.
 * timestamptz는 OffsetDateTime(UTC)로 넘긴다.
 */
@Repository
@RequiredArgsConstructor
public class RankingCloseRepository {

    private static final String UPSERT_BEST = """
            INSERT INTO ranking_best (user_id, rank, board_key, period_start)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE
            SET rank = excluded.rank,
                board_key = excluded.board_key,
                period_start = excluded.period_start
            WHERE excluded.rank < ranking_best.rank""";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    /** 마감을 표시한다. 같은 판·기간이 이미 있으면(다른 태스크가 먼저 확정) 아무것도 하지 않고 false. */
    public boolean insertClose(
            String boardKey, LocalDate periodStart, Instant closesAt, Instant closedAt, boolean skipped) {
        int inserted = jdbc.sql("""
                        INSERT INTO ranking_close (board_key, period_start, closes_at, closed_at, skipped)
                        VALUES (:boardKey, :periodStart, :closesAt, :closedAt, :skipped)
                        ON CONFLICT (board_key, period_start) DO NOTHING""")
                .param("boardKey", boardKey)
                .param("periodStart", periodStart)
                .param("closesAt", utc(closesAt))
                .param("closedAt", utc(closedAt))
                .param("skipped", skipped)
                .update();
        return inserted == 1;
    }

    public boolean isClosed(String boardKey, LocalDate periodStart) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM ranking_close WHERE board_key = :boardKey AND period_start = :periodStart)""")
                .param("boardKey", boardKey)
                .param("periodStart", periodStart)
                .query(Boolean.class)
                .single();
    }

    /** closesAt에 마감한 판 중 boardKeys에 든 것의 수 — 건너뛴 마감도 센다. */
    public int countClosedAt(Collection<String> boardKeys, Instant closesAt) {
        return jdbc.sql("SELECT count(*) FROM ranking_close WHERE board_key IN (:boardKeys) AND closes_at = :closesAt")
                .param("boardKeys", boardKeys)
                .param("closesAt", utc(closesAt))
                .query(Integer.class)
                .single();
    }

    public void insertRecord(
            long userId, String boardKey, LocalDate periodStart, Instant closesAt, int rank, BigDecimal value) {
        jdbc.sql("""
                        INSERT INTO ranking_record (user_id, board_key, period_start, closes_at, rank, value)
                        VALUES (:userId, :boardKey, :periodStart, :closesAt, :rank, :value)""")
                .param("userId", userId)
                .param("boardKey", boardKey)
                .param("periodStart", periodStart)
                .param("closesAt", utc(closesAt))
                .param("rank", rank)
                .param("value", value)
                .update();
    }

    /** 참가자 전원의 개인 최고 순위를 갱신한다 — 더 높은 순위일 때만 바꾸고 같으면 먼저 것을 둔다. userIds는 순위 순이다. */
    public void upsertBest(String boardKey, LocalDate periodStart, List<Long> userIdsInRankOrder) {
        if (userIdsInRankOrder.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(userIdsInRankOrder.size());
        for (int i = 0; i < userIdsInRankOrder.size(); i++) {
            rows.add(new Object[] {userIdsInRankOrder.get(i), i + 1, boardKey, periodStart});
        }
        jdbcTemplate.batchUpdate(UPSERT_BEST, rows);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS (PR ①의 랭킹 테스트도 새 TRUNCATE로 그대로 통과)

- [ ] **Step 9: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/resources/db/migration/V27__ranking_record.sql \
        src/main/java/project/study/ranking/RankingBoard.java \
        src/main/java/project/study/ranking/repository/RankingCloseRepository.java \
        src/test/java/project/study/ranking/RankingIntegrationTestBase.java \
        src/test/java/project/study/ranking/RankingBoardTest.java \
        src/test/java/project/study/ranking/RankingCloseRepositoryTest.java
git commit -m "feat: 랭킹 마감 기록 테이블과 마감 저장소를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 마감용 진행 중 조각 (집중 중 보정 없음)

**Files:**
- Modify: `src/main/java/project/study/studysession/service/ActiveStudySessionService.java` (`livePieces`, `toLivePieces`)
- Modify: `src/main/java/project/study/studysession/service/RankingSource.java`
- Modify: `src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java`

**Interfaces:**
- Consumes: `ActiveStudySessionService.livePieces(Instant)`, `isFocusing(...)` (PR ①)
- Produces:
  - `List<LivePiece> ActiveStudySessionService.livePieces(Instant asOf, boolean extendFocusing)` — false면 Δ 연장 없음, 모든 조각 `focusing=false`, `achievedAt = lastSeenAt`
  - `List<LivePiece> RankingSource.settledPieces(Instant asOf)` — `livePieces(asOf, false)` 위임

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java` 클래스 끝에 추가:

```java
    @Test
    void 보정을_끄면_집중_중이어도_보고된_스냅샷_그대로_나눈다() {
        Instant asOf = at(15, 15, 0, 0);
        Instant reportedAt = asOf.minusSeconds(10);
        draft(userId, asOf.minusSeconds(600), reportedAt, 590, "[]");

        assertThat(service.livePieces(asOf, false).stream()
                        .filter(p -> p.userId() == userId)
                        .toList())
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.focusSec()).isEqualTo(590);
                    assertThat(p.studySec()).isEqualTo(590);
                    assertThat(p.focusing()).isFalse();
                    assertThat(p.achievedAt()).isEqualTo(reportedAt);
                });
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionLivePiecesTest"`
Expected: 컴파일 실패 — `livePieces(Instant, boolean)`이 없다.

- [ ] **Step 3: 구현**

`ActiveStudySessionService.java` — 기존 `livePieces(Instant asOf)`의 Javadoc은 그대로 두고 몸통을 위임으로 바꾼 뒤, 아래 오버로드를 그 바로 뒤에 추가한다.
기존 `@Transactional(readOnly = true)`는 두 메서드 모두에 둔다(같은 클래스 안 호출이라 프록시를 거치지 않는다):

```java
    @Transactional(readOnly = true)
    public List<LivePiece> livePieces(Instant asOf) {
        return livePieces(asOf, true);
    }

    /**
     * extendFocusing이 false면 보고된 스냅샷 그대로 나누고 집중 중 표시도 끈다 — 마감 확정은 집중 중 보정 없이 계산한다(BY-828
     * ADR-0029). 앱 시계가 서버보다 늦으면 연장분이 마감 전 기간으로 들어가기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<LivePiece> livePieces(Instant asOf, boolean extendFocusing) {
        List<LivePiece> pieces = new ArrayList<>();
        for (ActiveStudySession draft : activeStudySessionRepository.findAll()) {
            try {
                pieces.addAll(toLivePieces(draft, asOf, extendFocusing));
            } catch (RuntimeException e) {
                log.warn("랭킹 집계에서 draft를 건너뜀: draftId={}", draft.getId(), e);
                Sentry.captureException(e);
            }
        }
        return pieces;
    }
```

`toLivePieces`를 아래로 바꾼다 — 바뀌는 곳은 시그니처의 `extendFocusing`과 `focusing` 판정 한 줄뿐이고, `extendSec`·`achievedAt`이
`focusing`을 따른다:

```java
    private List<LivePiece> toLivePieces(ActiveStudySession draft, Instant asOf, boolean extendFocusing) {
        List<StatusEvent> events =
                objectMapper.readValue(draft.getEvents(), new TypeReference<List<StatusEventRequest>>() {}).stream()
                        .map(StatusEventRequest::toEntity)
                        .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                        .toList();
        boolean focusing = extendFocusing && isFocusing(draft, events, asOf);
        int extendSec = focusing
                ? (int) Math.max(
                        0, Duration.between(draft.getLastSeenAt(), asOf).toSeconds())
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
                    achievedAt,
                    draft.getId()));
        }
        return pieces;
    }
```

`RankingSource.java` — `livePieces` 바로 뒤에 추가:

```java
    /** 마감 확정용 진행 중 조각 — 집중 중 보정 없이 보고된 스냅샷 그대로 나눈다(ADR-0029). */
    public List<LivePiece> settledPieces(Instant asOf) {
        return activeStudySessionService.livePieces(asOf, false);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.ActiveSessionLivePiecesTest" --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 5: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/studysession/service/ActiveStudySessionService.java \
        src/main/java/project/study/studysession/service/RankingSource.java \
        src/test/java/project/study/studysession/ActiveSessionLivePiecesTest.java
git commit -m "feat: 마감 확정용으로 집중 중 보정 없이 draft를 나눈다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 메달 규칙과 마감 쓰기

**Files:**
- Create: `src/main/java/project/study/ranking/close/Medals.java`
- Create: `src/main/java/project/study/ranking/close/RankingCloseWriter.java`
- Create: `src/test/java/project/study/ranking/close/MedalsTest.java`
- Create: `src/test/java/project/study/ranking/RankingCloseWriterTest.java`

**Interfaces:**
- Consumes: `Standings.entries()`, `Standings.of(...)`, `RankingEntry.of(long, String, double, Instant)`, `RankedEntry(int rank, RankingEntry entry)`, `RankingCalendar.Window(LocalDate start, LocalDate end, Instant closesAt)` (PR ①), `RankingCloseRepository` (Task 1)
- Produces:
  - `Medals.PODIUM = 3`, `Medals.MIN_TIME_VALUE = 1800`(long)
  - `static List<RankedEntry> Medals.of(RankingBoard board, Standings standings)`
  - `static boolean Medals.qualifies(RankingBoard board, double value)`
  - `static BigDecimal Medals.recordValue(double value)`
  - `boolean RankingCloseWriter.write(RankingBoard board, Window window, Instant closedAt, Standings standings)` — `@Transactional`, 이미 확정됐으면 false

- [ ] **Step 1: 실패하는 테스트 작성 — 메달 규칙(단위)**

`src/test/java/project/study/ranking/close/MedalsTest.java`:

```java
package project.study.ranking.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;

class MedalsTest {

    private static final RankingBoard DAILY = new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.DAILY, null);
    private static final RankingBoard RATE = new RankingBoard(RankingBoardType.FOCUS_RATE, RankingPeriod.WEEKLY, null);
    private static final Instant T = Instant.parse("2026-10-09T15:00:00Z");

    /** userId는 인자 순서(1부터), 정렬은 Standings가 한다. */
    private static Standings standings(double... values) {
        List<RankingEntry> entries = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            entries.add(RankingEntry.of(i + 1, "u" + (i + 1), values[i], T));
        }
        return Standings.of(entries, T);
    }

    @Test
    void 시간_판은_30분_미만이면_메달이_없고_다음_순위로_당기지_않는다() {
        assertThat(Medals.of(DAILY, standings(2400, 1799, 1900, 1000)))
                .extracting(RankedEntry::rank, medal -> medal.entry().userId())
                .containsExactly(tuple(1, 1L), tuple(2, 3L));
    }

    @Test
    void 일등이_30분_미만이면_아무도_받지_않는다() {
        assertThat(Medals.of(DAILY, standings(1700, 1000))).isEmpty();
    }

    @Test
    void 정확히_30분이면_받는다() {
        assertThat(Medals.of(DAILY, standings(1800))).extracting(RankedEntry::rank).containsExactly(1);
    }

    @Test
    void 집중률_판은_값과_상관없이_3위까지_받는다() {
        assertThat(Medals.of(RATE, standings(91.2, 88.0, 75.5, 60.0)))
                .extracting(RankedEntry::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void 기록_값은_소수_1자리로_반올림한다() {
        assertThat(Medals.recordValue(90.909)).isEqualByComparingTo("90.9");
        assertThat(Medals.recordValue(90.95)).isEqualByComparingTo("91.0");
        assertThat(Medals.recordValue(3000)).isEqualByComparingTo("3000.0");
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 — 마감 쓰기(통합)**

`src/test/java/project/study/ranking/RankingCloseWriterTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.close.RankingCloseWriter;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;

/** 마감 표시·메달·개인 최고를 한 트랜잭션에 쓰고, 이미 확정된 판에는 아무것도 쓰지 않는다 (BY-828). */
class RankingCloseWriterTest extends RankingIntegrationTestBase {

    private static final RankingBoard DAILY = new RankingBoard(RankingBoardType.FOCUS_TIME, RankingPeriod.DAILY, null);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Autowired
    private RankingCloseWriter writer;

    private static Window friday() {
        return new Window(FRI, FRI, kst(10, 10, 0, 0));
    }

    @Test
    void 마감_표시_메달_개인_최고를_함께_쓰고_두_번째_쓰기는_아무것도_남기지_않는다() {
        long a = user("a");
        long b = user("b");
        long c = user("c");
        Standings standings = Standings.of(
                List.of(
                        RankingEntry.of(a, "a", 3000, kst(10, 9, 10, 0)),
                        RankingEntry.of(b, "b", 1700, kst(10, 9, 14, 0)),
                        RankingEntry.of(c, "c", 2400, kst(10, 9, 12, 0))),
                kst(10, 10, 0, 1));

        assertThat(writer.write(DAILY, friday(), kst(10, 10, 0, 1), standings)).isTrue();
        assertThat(writer.write(DAILY, friday(), kst(10, 10, 0, 2), standings)).isFalse();

        assertThat(jdbc.queryForList("SELECT user_id, rank, value FROM ranking_record ORDER BY rank"))
                .extracting(
                        row -> row.get("user_id"),
                        row -> row.get("rank"),
                        row -> ((BigDecimal) row.get("value")).toPlainString())
                .containsExactly(tuple(a, 1, "3000.0"), tuple(c, 2, "2400.0"));
        assertThat(jdbc.queryForObject("SELECT closed_at FROM ranking_close", OffsetDateTime.class)
                        .toInstant())
                .isEqualTo(kst(10, 10, 0, 1));
        assertThat(jdbc.queryForList("SELECT user_id, rank FROM ranking_best ORDER BY rank"))
                .extracting(row -> row.get("user_id"), row -> row.get("rank"))
                .containsExactly(tuple(a, 1), tuple(c, 2), tuple(b, 3));
    }

    @Test
    void 참가자가_없어도_마감을_표시한다() {
        assertThat(writer.write(DAILY, friday(), kst(10, 10, 0, 1), Standings.of(List.of(), kst(10, 10, 0, 1))))
                .isTrue();

        assertThat(jdbc.queryForObject("SELECT skipped FROM ranking_close", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ranking_record", Integer.class)).isZero();
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.close.MedalsTest" --tests "project.study.ranking.RankingCloseWriterTest"`
Expected: 컴파일 실패 — `Medals`·`RankingCloseWriter`가 없다.

- [ ] **Step 4: 메달 규칙 구현**

`src/main/java/project/study/ranking/close/Medals.java`:

```java
package project.study.ranking.close;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;

/**
 * 마감 메달 규칙 (BY-828, 2026-10-10 기획 변경) — 1~3위 중 순공·시간대 판은 1800초(30분) 이상만 받는다. 못 받은 자리를 다음
 * 순위로 당기지 않는다(1위 40분·2위 20분이면 1위만). 집중률은 참가 조건(주 10시간·월 30시간)이 이미 있어 따로 걸지 않는다.
 */
public final class Medals {

    public static final int PODIUM = 3;

    /** 순공·시간대 판의 메달 최소 값(초). */
    public static final long MIN_TIME_VALUE = 1800;

    private Medals() {}

    /** 메달을 받는 줄과 그 순위 — 순위는 순위표 그대로다. */
    public static List<RankedEntry> of(RankingBoard board, Standings standings) {
        List<RankingEntry> entries = standings.entries();
        List<RankedEntry> medals = new ArrayList<>(PODIUM);
        for (int i = 0; i < Math.min(PODIUM, entries.size()); i++) {
            if (qualifies(board, entries.get(i).value())) {
                medals.add(new RankedEntry(i + 1, entries.get(i)));
            }
        }
        return medals;
    }

    public static boolean qualifies(RankingBoard board, double value) {
        return board.type() == RankingBoardType.FOCUS_RATE || value >= MIN_TIME_VALUE;
    }

    /** 기록 값 — 시간 판은 초, 집중률은 %를 소수 1자리로 반올림한다(응답 표기와 같다). */
    public static BigDecimal recordValue(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 5: 마감 쓰기 구현**

`src/main/java/project/study/ranking/close/RankingCloseWriter.java`:

```java
package project.study.ranking.close;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.engine.RankedEntry;
import project.study.ranking.engine.RankingEntry;
import project.study.ranking.engine.Standings;
import project.study.ranking.repository.RankingCloseRepository;

/**
 * 판 하나의 마감을 한 트랜잭션에 쓴다 (BY-828, ADR-0029) — 마감 표시, 메달, 참가자 전원의 개인 최고. 순위표 계산은 이 트랜잭션
 * 밖에서 끝내고 넘긴다 — 계산이 자체 REPEATABLE_READ 트랜잭션을 열어야 해서 쓰기 트랜잭션 안에서 부를 수 없다(ADR-0028 결정 8).
 */
@Component
@RequiredArgsConstructor
public class RankingCloseWriter {

    private final RankingCloseRepository closes;

    /** 다른 태스크가 같은 판·기간을 먼저 확정했으면 아무것도 쓰지 않고 false — 마감 표시의 PK가 하나만 통과시킨다. */
    @Transactional
    public boolean write(RankingBoard board, Window window, Instant closedAt, Standings standings) {
        if (!closes.insertClose(board.key(), window.start(), window.closesAt(), closedAt, false)) {
            return false;
        }
        for (RankedEntry medal : Medals.of(board, standings)) {
            closes.insertRecord(
                    medal.entry().userId(),
                    board.key(),
                    window.start(),
                    window.closesAt(),
                    medal.rank(),
                    Medals.recordValue(medal.entry().value()));
        }
        closes.upsertBest(
                board.key(),
                window.start(),
                standings.entries().stream().map(RankingEntry::userId).toList());
        return true;
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.close.MedalsTest" --tests "project.study.ranking.RankingCloseWriterTest"`
Expected: PASS

- [ ] **Step 7: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/close/ \
        src/test/java/project/study/ranking/close/MedalsTest.java \
        src/test/java/project/study/ranking/RankingCloseWriterTest.java
git commit -m "feat: 마감 메달 규칙과 판 하나의 마감 쓰기를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 마감 확정 루프와 스케줄러

**Files:**
- Create: `src/main/java/project/study/ranking/close/RankingCloser.java`
- Create: `src/main/java/project/study/ranking/scheduler/RankingCloseScheduler.java`
- Modify: `src/test/resources/application.yaml` (스케줄러 끔)
- Create: `src/test/java/project/study/ranking/RankingCloserTest.java`
- Create: `src/test/java/project/study/ranking/scheduler/RankingCloseSchedulerTest.java`

**Interfaces:**
- Consumes: `StandingsCalculator.compute(RankingBoard, Window, Instant asOf, List<LivePiece>, Long onlyUserId)`, `RankingCalendar.window(RankingBoard, Instant, int offset)`, `Standings.of(...)` (PR ①); `RankingSource.settledPieces(Instant)` (Task 2); `RankingCloseRepository.isClosed/insertClose` (Task 1); `RankingCloseWriter.write(...)` (Task 3)
- Produces:
  - `void RankingCloser.closeDue(Instant now)` — 트랜잭션 없음. 판 하나가 실패해도 나머지는 계속
  - `RankingCloser.SETTLE_DELAY = 1분`, `RankingCloser.CATCH_UP_LIMIT = 1시간`
  - `RankingCloseScheduler.closeDueBoards()` — `@Scheduled(cron = "5 * * * * *", zone = "Asia/Seoul")`, `app.ranking.close.enabled`(기본 켜짐)

- [ ] **Step 1: 테스트 설정에서 스케줄러를 끈다**

`src/test/resources/application.yaml`의 `app:` 아래(`room:`과 같은 깊이)에 추가:

```yaml
  # BY-828 랭킹 마감 스케줄러 — 테스트는 RankingCloser.closeDue를 직접 부른다 (매분 백그라운드 마감이 테스트 데이터를 건드리는 경합 방지)
  ranking:
    close:
      enabled: false
```

- [ ] **Step 2: 실패하는 테스트 작성 — 마감 확정(통합)**

`src/test/java/project/study/ranking/RankingCloserTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.ranking.close.RankingCloser;

/** 마감 1분 뒤 직전 기간을 확정하고, 1시간이 넘으면 건너뛴다 (BY-828, ADR-0029). 기준 시각은 closeDue 인자다. */
class RankingCloserTest extends RankingIntegrationTestBase {

    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    @Autowired
    private RankingCloser closer;

    /** "판 닉네임 순위 값" — 판·순위 순. */
    private List<String> records() {
        return jdbc.queryForList(
                """
                SELECT r.board_key || ' ' || u.nickname || ' ' || r.rank || ' ' || r.value
                FROM ranking_record r JOIN users u ON u.id = r.user_id
                ORDER BY r.board_key, r.rank""",
                String.class);
    }

    /** 마감 표시의 skipped — 표시가 없으면 null. */
    private Boolean skipped(String boardKey, LocalDate periodStart) {
        return jdbc.query(
                "SELECT skipped FROM ranking_close WHERE board_key = ? AND period_start = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null,
                boardKey,
                periodStart);
    }

    @Test
    void 마감_1분_뒤_일간판을_확정하고_30분_이상인_1_3위만_메달을_받는다() {
        long a = user("a");
        long b = user("b");
        long c = user("c");
        session(a, kst(10, 9, 9, 0), 60, 3000); // 오전
        session(c, kst(10, 9, 13, 0), 60, 2400); // 오후
        session(b, kst(10, 9, 14, 0), 30, 1700); // 오후, 30분 미만 — 3위여도 메달 없음

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(records())
                .containsExactly(
                        "FOCUS_TIME:DAILY a 1 3000.0",
                        "FOCUS_TIME:DAILY c 2 2400.0",
                        "TIME_SLOT:DAILY:AFTERNOON c 1 2400.0",
                        "TIME_SLOT:DAILY:MORNING a 1 3000.0");
        assertThat(skipped("FOCUS_TIME:DAILY", FRI)).isFalse();
    }

    @Test
    void 마감_1분이_지나기_전에는_확정하지_않는다() {
        session(user("a"), kst(10, 9, 9, 0), 60, 3000);

        closer.closeDue(kst(10, 10, 0, 0).plusSeconds(59));

        assertThat(skipped("FOCUS_TIME:DAILY", FRI)).isNull();
        assertThat(records()).isEmpty();
    }

    @Test
    void 다시_돌려도_확정된_기록은_늦은_제출로_바뀌지_않는다() {
        session(user("a"), kst(10, 9, 9, 0), 60, 3000);
        closer.closeDue(kst(10, 10, 0, 1));
        session(user("b"), kst(10, 9, 10, 0), 90, 5000); // 마감 뒤에 들어온 늦은 제출

        closer.closeDue(kst(10, 10, 0, 2));

        assertThat(records()).containsExactly("FOCUS_TIME:DAILY a 1 3000.0", "TIME_SLOT:DAILY:MORNING a 1 3000.0");
    }

    @Test
    void 마감_1시간이_지나면_기록하지_않고_건너뛴_것으로_표시한다() {
        session(user("a"), kst(10, 9, 9, 0), 60, 3000);

        closer.closeDue(kst(10, 10, 1, 1));

        assertThat(skipped("FOCUS_TIME:DAILY", FRI)).isTrue();
        assertThat(records()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ranking_best", Integer.class)).isZero();
    }

    @Test
    void 진행_중_draft는_보정_없이_보고된_값을_마감_시각까지만_더한다() {
        long a = user("a");
        // 앱 시계가 서버보다 70초 늦다 — 보고 시각은 자정 전, 서버 수신은 자정 뒤라 마감 계산 시각(00:01)에도 집중 중이다.
        // 보정하면 보고 뒤 20초가 금요일에 더해져 3590이 된다
        activeStudySessionRepository.upsertSnapshot(
                a, kst(10, 9, 23, 0), kst(10, 9, 23, 59).plusSeconds(30), kst(10, 10, 0, 0).plusSeconds(40), 3570, 3570,
                "[]");

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(records()).containsExactly("FOCUS_TIME:DAILY a 1 3570.0");
    }

    @Test
    void 탈퇴한_사용자는_마감에서_빠지고_다음_사람이_1위다() {
        long a = user("a");
        long b = user("b");
        session(a, kst(10, 9, 9, 0), 60, 3500);
        session(b, kst(10, 9, 13, 0), 60, 3000);
        withdraw(a);

        closer.closeDue(kst(10, 10, 0, 1));

        assertThat(records()).containsExactly("FOCUS_TIME:DAILY b 1 3000.0", "TIME_SLOT:DAILY:AFTERNOON b 1 3000.0");
    }

    @Test
    void 집중률_주간판은_30분_조건_없이_소수_1자리로_기록한다() {
        session(user("a"), kst(9, 29, 8, 0), 660, 36_000); // 11시간 중 순공 10시간 — 90.909%

        closer.closeDue(kst(10, 5, 0, 1));

        assertThat(records()).contains("FOCUS_RATE:WEEKLY a 1 90.9", "FOCUS_TIME:WEEKLY a 1 36000.0");
    }
}
```

- [ ] **Step 3: 실패하는 테스트 작성 — 스케줄러(단위)**

`src/test/java/project/study/ranking/scheduler/RankingCloseSchedulerTest.java`:

```java
package project.study.ranking.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.ranking.close.RankingCloser;

class RankingCloseSchedulerTest {

    @Test
    void 지금_시각으로_마감을_돌리고_예외는_삼킨다() {
        Instant now = Instant.parse("2026-10-09T15:01:05Z");
        List<Instant> calls = new ArrayList<>();
        RankingCloser closer = new RankingCloser(null, null, null, null) {
            @Override
            public void closeDue(Instant at) {
                calls.add(at);
                throw new IllegalStateException("마감 실패");
            }
        };
        RankingCloseScheduler scheduler = new RankingCloseScheduler(closer, Clock.fixed(now, ZoneOffset.UTC));

        assertThatCode(scheduler::closeDueBoards).doesNotThrowAnyException();
        assertThat(calls).containsExactly(now);
    }
}
```

- [ ] **Step 4: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingCloserTest" --tests "project.study.ranking.scheduler.RankingCloseSchedulerTest"`
Expected: 컴파일 실패 — `RankingCloser`·`RankingCloseScheduler`가 없다.

- [ ] **Step 5: 마감 확정 루프 구현**

`src/main/java/project/study/ranking/close/RankingCloser.java`:

```java
package project.study.ranking.close;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingCalendar;
import project.study.ranking.RankingCalendar.Window;
import project.study.ranking.engine.Standings;
import project.study.ranking.engine.StandingsCalculator;
import project.study.ranking.repository.RankingCloseRepository;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.service.RankingSource;

/**
 * 직전 기간이 마감된 판을 확정한다 (BY-828, ADR-0029). 판 하나 = 계산 한 번(트랜잭션 밖, 엔진이 자체 REPEATABLE_READ 트랜잭션을
 * 연다) + 쓰기 트랜잭션 하나. 이 클래스는 트랜잭션을 걸지 않는다 — 걸면 엔진의 격리 수준 보장이 사라져 바로 실패한다(ADR-0028 결정 8).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingCloser {

    /** 마감 뒤 이만큼 기다렸다 확정한다 — 자정 뒤 첫 스냅샷(하트비트 30초 + flush 5초 + 네트워크)이 들어오는 시간. */
    public static final Duration SETTLE_DELAY = Duration.ofMinutes(1);

    /** 이보다 늦으면 확정하지 않고 건너뛴다 — 첫 배포 때 지난 기간에 메달이 소급되지 않고, 04시 보류가 영영 막히지 않게. */
    public static final Duration CATCH_UP_LIMIT = Duration.ofHours(1);

    private final StandingsCalculator calculator;
    private final RankingSource source;
    private final RankingCloseRepository closes;
    private final RankingCloseWriter writer;

    /** now 기준 직전 기간이 마감 + 1분을 지났고 아직 마감 표시가 없는 판을 확정한다. 판 하나가 실패해도 나머지는 계속한다. */
    public void closeDue(Instant now) {
        List<LivePiece> pieces = null;
        for (RankingBoard board : RankingBoard.closable()) {
            Window window = RankingCalendar.window(board, now, -1);
            if (now.isBefore(window.closesAt().plus(SETTLE_DELAY)) || closes.isClosed(board.key(), window.start())) {
                continue;
            }
            try {
                if (now.isAfter(window.closesAt().plus(CATCH_UP_LIMIT))) {
                    skip(board, window, now);
                    continue;
                }
                if (pieces == null) {
                    pieces = source.settledPieces(now);
                }
                close(board, window, now, pieces);
            } catch (RuntimeException e) {
                log.error("랭킹 마감 실패 — 다음 틱에 재시도: board={}, periodStart={}", board.key(), window.start(), e);
                Sentry.captureException(e);
            }
        }
    }

    private void close(RankingBoard board, Window window, Instant now, List<LivePiece> pieces) {
        Standings standings = Standings.of(calculator.compute(board, window, now, pieces, null), now);
        if (writer.write(board, window, now, standings)) {
            log.info(
                    "랭킹 마감 확정: board={}, periodStart={}, participants={}",
                    board.key(),
                    window.start(),
                    standings.entries().size());
        }
    }

    private void skip(RankingBoard board, Window window, Instant now) {
        if (closes.insertClose(board.key(), window.start(), window.closesAt(), now, true)) {
            log.warn("랭킹 마감을 1시간 안에 확정하지 못해 건너뜀: board={}, periodStart={}", board.key(), window.start());
            Sentry.captureMessage(
                    "랭킹 마감 건너뜀: " + board.key() + " " + window.start(), SentryLevel.WARNING);
        }
    }
}
```

- [ ] **Step 6: 스케줄러 구현**

`src/main/java/project/study/ranking/scheduler/RankingCloseScheduler.java`:

```java
package project.study.ranking.scheduler;

import io.sentry.Sentry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.ranking.close.RankingCloser;

/**
 * 랭킹 마감 스케줄러 (BY-828, ADR-0029) — 매분 5초(KST)에 마감할 판을 확정한다. 00시 마감분은 00:01:05에, 재기동으로 놓친 판은
 * 다음 틱에 따라잡는다. 예외를 직접 잡아 Sentry로 올린다 — @Scheduled 밖으로 나가면 Spring이 로그만 남기고 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ranking.close.enabled", havingValue = "true", matchIfMissing = true)
public class RankingCloseScheduler {

    private final RankingCloser closer;
    private final Clock clock;

    @Scheduled(cron = "5 * * * * *", zone = "Asia/Seoul")
    public void closeDueBoards() {
        try {
            closer.closeDue(clock.instant());
        } catch (Exception e) {
            log.error("랭킹 마감 스케줄 실패", e);
            Sentry.captureException(e);
        }
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 8: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/close/RankingCloser.java \
        src/main/java/project/study/ranking/scheduler/RankingCloseScheduler.java \
        src/test/resources/application.yaml \
        src/test/java/project/study/ranking/RankingCloserTest.java \
        src/test/java/project/study/ranking/scheduler/RankingCloseSchedulerTest.java
git commit -m "feat: 마감 1분 뒤 랭킹판을 확정하는 스케줄러를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 기록 목록 API (모두 보기)

**Files:**
- Create: `src/main/java/project/study/ranking/dto/RecordCursor.java`
- Create: `src/main/java/project/study/ranking/dto/RankingRecordRow.java`
- Create: `src/main/java/project/study/ranking/dto/RankingRecordItem.java`
- Create: `src/main/java/project/study/ranking/dto/RankingRecordPageResponse.java`
- Create: `src/main/java/project/study/ranking/repository/RankingRecordQueries.java`
- Create: `src/main/java/project/study/ranking/service/RecordItems.java`
- Create: `src/main/java/project/study/ranking/service/RankingRecordService.java`
- Create: `src/main/java/project/study/ranking/controller/RankingRecordController.java`
- Create: `src/test/java/project/study/ranking/RecordCursorTest.java`
- Create: `src/test/java/project/study/ranking/RankingRecordTestBase.java`
- Create: `src/test/java/project/study/ranking/RankingRecordListApiTest.java`

**Interfaces:**
- Consumes: `RankingBoard.fromKey` (Task 1), `Medals.PODIUM` (Task 3), `BoardValues.value(RankingBoardType, double)` (PR ①, `ranking.service` 패키지 전용)
- Produces:
  - `record RecordCursor(Instant closesAt, long id)` — `String encode()`, `static RecordCursor decode(String)`(깨지면 `BadRequestException`)
  - `record RankingRecordRow(long id, String boardKey, LocalDate periodStart, Instant closesAt, int rank, BigDecimal value)`
  - `record RankingRecordItem(long id, RankingBoardType type, RankingPeriod period, TimeSlot slot, LocalDate periodStart, int rank, Number value, Instant closesAt)`
  - `record RankingRecordPageResponse(List<RankingRecordItem> items, String nextCursor)`
  - `List<RankingRecordRow> RankingRecordQueries.page(long userId, Integer rank, RankingBoardType type, RecordCursor after, int limit)`
  - `static RankingRecordItem RecordItems.of(RankingRecordRow row)` (패키지 전용)
  - `RankingRecordPageResponse RankingRecordService.list(long userId, Integer rank, RankingBoardType type, String cursor, int size)`
  - `GET /api/rankings/records?rank=&type=&cursor=&size=` (version 1)
  - 테스트 기반 `RankingRecordTestBase`: `get(uri, userId)`, `post(uri, userId, json)`, `record(...)`, `closed(...)`, `best(...)`

- [ ] **Step 1: 실패하는 테스트 작성 — 커서(단위)**

`src/test/java/project/study/ranking/RecordCursorTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import project.study.common.exception.BadRequestException;
import project.study.ranking.dto.RecordCursor;

class RecordCursorTest {

    @Test
    void 인코딩한_커서를_되돌린다() {
        RecordCursor cursor = new RecordCursor(Instant.parse("2026-10-09T15:00:00Z"), 31);

        assertThat(RecordCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    // 깨진 base64, "nope", "123", "a:b", "9223372036854775807:1"(범위 밖 시각)
    @ParameterizedTest
    @ValueSource(strings = {"!!!", "bm9wZQ", "MTIz", "YTpi", "OTIyMzM3MjAzNjg1NDc3NTgwNzox"})
    void 형식이_틀리면_400이다(String value) {
        assertThatThrownBy(() -> RecordCursor.decode(value)).isInstanceOf(BadRequestException.class);
    }
}
```

- [ ] **Step 2: 기록 API 테스트 기반 작성**

`src/test/java/project/study/ranking/RankingRecordTestBase.java`:

```java
package project.study.ranking;

import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

/** 마감 기록 API 테스트 공통 (BY-828) — 기록·마감 표시·개인 최고를 SQL로 바로 넣고, 새 경로 기본 버전(1)으로 요청한다. */
abstract class RankingRecordTestBase extends RankingIntegrationTestBase {

    @Autowired
    protected MockMvcTester mvc;

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    protected MockMvcRequestBuilder get(String uri, long userId) {
        return mvc.get()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    protected MockMvcRequestBuilder post(String uri, long userId, String json) {
        return mvc.post()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected long record(long userId, String boardKey, LocalDate periodStart, Instant closesAt, int rank, String value) {
        return jdbc.queryForObject(
                """
                INSERT INTO ranking_record (user_id, board_key, period_start, closes_at, rank, value)
                VALUES (?, ?, ?, ?, ?, ?::numeric) RETURNING id""",
                Long.class,
                userId,
                boardKey,
                periodStart,
                closesAt.atOffset(ZoneOffset.UTC),
                rank,
                value);
    }

    protected void closed(String boardKey, LocalDate periodStart, Instant closesAt, boolean skipped) {
        jdbc.update(
                "INSERT INTO ranking_close (board_key, period_start, closes_at, closed_at, skipped) VALUES (?, ?, ?, ?, ?)",
                boardKey,
                periodStart,
                closesAt.atOffset(ZoneOffset.UTC),
                closesAt.plusSeconds(65).atOffset(ZoneOffset.UTC),
                skipped);
    }

    protected void best(long userId, int rank, String boardKey, LocalDate periodStart) {
        jdbc.update(
                "INSERT INTO ranking_best (user_id, rank, board_key, period_start) VALUES (?, ?, ?, ?)",
                userId,
                rank,
                boardKey,
                periodStart);
    }
}
```

- [ ] **Step 3: 실패하는 테스트 작성 — 목록 API**

`src/test/java/project/study/ranking/RankingRecordListApiTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import project.study.config.ApiVersionConfig;

class RankingRecordListApiTest extends RankingRecordTestBase {

    private static final String RECORDS = "/api/rankings/records";
    private static final LocalDate WED = LocalDate.of(2026, 10, 7);
    private static final LocalDate THU = LocalDate.of(2026, 10, 8);

    @Test
    void 최신_마감부터_커서로_나눠_주고_마지막_페이지는_nextCursor가_없다() throws Exception {
        long me = user("me");
        long rate = record(me, "FOCUS_RATE:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 3, "90.9");
        long daily = record(me, "FOCUS_TIME:DAILY", WED, kst(10, 8, 0, 0), 2, "2400.0");
        long slot = record(me, "TIME_SLOT:DAILY:MORNING", THU, kst(10, 9, 0, 0), 1, "11060.0");

        String first = get(RECORDS + "?size=2", me).exchange().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(first, "$.items[*].id")).containsExactly((int) slot, (int) daily);
        assertThat(JsonPath.<String>read(first, "$.items[0].type")).isEqualTo("TIME_SLOT");
        assertThat(JsonPath.<String>read(first, "$.items[0].period")).isEqualTo("DAILY");
        assertThat(JsonPath.<String>read(first, "$.items[0].slot")).isEqualTo("MORNING");
        assertThat(JsonPath.<String>read(first, "$.items[0].periodStart")).isEqualTo("2026-10-08");
        assertThat(JsonPath.<Integer>read(first, "$.items[0].rank")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(first, "$.items[0].value")).isEqualTo(11060);
        assertThat(JsonPath.<String>read(first, "$.items[0].closesAt")).isEqualTo("2026-10-08T15:00:00Z");

        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(get(RECORDS + "?size=2&cursor=" + cursor, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[0].id", v -> assertThat(v).isEqualTo((int) rate))
                .hasPathSatisfying("$.items[0].value", v -> assertThat(v).isEqualTo(90.9))
                .hasPathSatisfying("$.items[0].slot", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.nextCursor", v -> assertThat(v).isNull());
    }

    @Test
    void 같은_마감_시각의_기록이_페이지_경계에_걸려도_빠지거나_겹치지_않는다() throws Exception {
        long me = user("me");
        Instant closesAt = kst(10, 9, 0, 0);
        long a = record(me, "FOCUS_TIME:DAILY", THU, closesAt, 1, "5000.0");
        long b = record(me, "TIME_SLOT:DAILY:MORNING", THU, closesAt, 1, "3000.0");
        long c = record(me, "TIME_SLOT:DAILY:EVENING", THU, closesAt, 2, "2000.0");

        List<Integer> ids = new ArrayList<>();
        String cursor = null;
        do {
            String uri = RECORDS + "?size=1" + (cursor == null ? "" : "&cursor=" + cursor);
            String body = get(uri, me).exchange().getResponse().getContentAsString();
            ids.addAll(JsonPath.<List<Integer>>read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
        } while (cursor != null);

        assertThat(ids).containsExactly((int) c, (int) b, (int) a);
    }

    @Test
    void 순위와_종목으로_거르고_남의_기록은_주지_않는다() {
        long me = user("me");
        long other = user("other");
        record(me, "FOCUS_TIME:DAILY", WED, kst(10, 8, 0, 0), 1, "5000.0");
        long slot2 = record(me, "TIME_SLOT:DAILY:MORNING", WED, kst(10, 8, 0, 0), 2, "3000.0");
        record(me, "FOCUS_TIME:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 2, "40000.0");
        record(other, "TIME_SLOT:DAILY:EVENING", WED, kst(10, 8, 0, 0), 2, "2000.0");

        assertThat(get(RECORDS + "?rank=2&type=TIME_SLOT", me))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items[*].id")
                .asArray()
                .containsExactly((int) slot2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rank=0", "rank=4", "type=TOTAL_TIME", "type=NOPE", "size=0", "size=51", "cursor=bm9wZQ"})
    void 범위_밖_값은_400이다(String query) {
        assertThat(get(RECORDS + "?" + query, user("me"))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.get().uri(RECORDS).header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 4: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.RecordCursorTest" --tests "project.study.ranking.RankingRecordListApiTest"`
Expected: 컴파일 실패 — `RecordCursor` 등이 없다.

- [ ] **Step 5: DTO 작성**

`src/main/java/project/study/ranking/dto/RecordCursor.java`:

```java
package project.study.ranking.dto;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import project.study.common.exception.BadRequestException;

/**
 * 기록 목록의 다음 페이지 위치 (BY-828) — (closesAt, id) 내림차순의 마지막 항목. FE에는 불투명한 문자열로 준다. 마감 시각은
 * 정각(00시·04시)이라 초 단위로 담는다.
 */
public record RecordCursor(Instant closesAt, long id) {

    public String encode() {
        String raw = closesAt.getEpochSecond() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 형식이 틀리면 400. */
    public static RecordCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            return new RecordCursor(
                    Instant.ofEpochSecond(Long.parseLong(raw.substring(0, separator))),
                    Long.parseLong(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeException e) {
            throw new BadRequestException("cursor 형식이 올바르지 않습니다");
        }
    }
}
```

`src/main/java/project/study/ranking/dto/RankingRecordRow.java`:

```java
package project.study.ranking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 마감 기록 한 행 (BY-828). value는 초 또는 집중률(%). */
public record RankingRecordRow(
        long id, String boardKey, LocalDate periodStart, Instant closesAt, int rank, BigDecimal value) {}
```

`src/main/java/project/study/ranking/dto/RankingRecordItem.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 마감 기록 항목 (BY-828) — 1·2·3위로 마감한 판 하나. */
@Schema(description = "마감 기록 항목 — 1·2·3위로 마감한 판 하나")
public record RankingRecordItem(
        long id,
        RankingBoardType type,
        RankingPeriod period,
        @Schema(description = "시간대 판만, 아니면 null") TimeSlot slot,
        @Schema(description = "기간 시작일(KST)") LocalDate periodStart,
        @Schema(description = "1·2·3") int rank,
        @Schema(description = "시간 판 초, 집중률 %(소수 1자리)") Number value,
        @Schema(description = "마감 시각") Instant closesAt) {}
```

`src/main/java/project/study/ranking/dto/RankingRecordPageResponse.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 마감 기록 목록 (BY-828) — (마감 시각, id) 내림차순. */
@Schema(description = "마감 기록 목록 — (마감 시각, id) 내림차순")
public record RankingRecordPageResponse(
        List<RankingRecordItem> items,
        @Schema(description = "다음 페이지 커서 — 마지막 페이지면 null") String nextCursor) {}
```

- [ ] **Step 6: 기록 읽기 쿼리 작성**

`src/main/java/project/study/ranking/repository/RankingRecordQueries.java`:

```java
package project.study.ranking.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.ranking.RankingBoardType;
import project.study.ranking.dto.RankingRecordRow;
import project.study.ranking.dto.RecordCursor;

/** 마감 기록 읽기 (BY-828) — 모두 본인 기록만 읽는다. timestamptz는 OffsetDateTime(UTC)로 넘긴다. */
@Repository
@RequiredArgsConstructor
public class RankingRecordQueries {

    private static final String COLUMNS = "id, board_key, period_start, closes_at, rank, value";

    private static final RowMapper<RankingRecordRow> ROW = (rs, i) -> new RankingRecordRow(
            rs.getLong("id"),
            rs.getString("board_key"),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("closes_at", OffsetDateTime.class).toInstant(),
            rs.getInt("rank"),
            rs.getBigDecimal("value"));

    private final JdbcClient jdbc;

    /** 내 기록 — (마감 시각, id) 내림차순. rank·type은 주면 거르고, after는 그 항목 다음부터. */
    public List<RankingRecordRow> page(
            long userId, Integer rank, RankingBoardType type, RecordCursor after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM ranking_record WHERE user_id = :userId");
        MapSqlParameterSource params =
                new MapSqlParameterSource("userId", userId).addValue("limit", limit);
        if (rank != null) {
            sql.append(" AND rank = :rank");
            params.addValue("rank", rank);
        }
        if (type != null) {
            sql.append(" AND split_part(board_key, ':', 1) = :type");
            params.addValue("type", type.name());
        }
        if (after != null) {
            sql.append(" AND (closes_at, id) < (:afterClosesAt, :afterId)");
            params.addValue("afterClosesAt", after.closesAt().atOffset(ZoneOffset.UTC))
                    .addValue("afterId", after.id());
        }
        sql.append(" ORDER BY closes_at DESC, id DESC LIMIT :limit");
        return jdbc.sql(sql.toString()).paramSource(params).query(ROW).list();
    }
}
```

- [ ] **Step 7: 서비스 작성**

`src/main/java/project/study/ranking/service/RecordItems.java`:

```java
package project.study.ranking.service;

import project.study.ranking.RankingBoard;
import project.study.ranking.dto.RankingRecordItem;
import project.study.ranking.dto.RankingRecordRow;

/** 기록 행 → 응답 항목 (BY-828) — board_key를 판으로 되돌리고 값을 판의 표기(집중률 소수 1자리, 나머지 정수)로 바꾼다. */
final class RecordItems {

    private RecordItems() {}

    static RankingRecordItem of(RankingRecordRow row) {
        RankingBoard board = RankingBoard.fromKey(row.boardKey());
        return new RankingRecordItem(
                row.id(),
                board.type(),
                board.period(),
                board.slot(),
                row.periodStart(),
                row.rank(),
                BoardValues.value(board.type(), row.value().doubleValue()),
                row.closesAt());
    }
}
```

`src/main/java/project/study/ranking/service/RankingRecordService.java`:

```java
package project.study.ranking.service;

import static project.study.ranking.RankingBoardType.FOCUS_RATE;
import static project.study.ranking.RankingBoardType.FOCUS_TIME;
import static project.study.ranking.RankingBoardType.TIME_SLOT;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.ranking.RankingBoardType;
import project.study.ranking.close.Medals;
import project.study.ranking.dto.RankingRecordPageResponse;
import project.study.ranking.dto.RankingRecordRow;
import project.study.ranking.dto.RecordCursor;
import project.study.ranking.repository.RankingRecordQueries;

/** 랭킹 마감 기록 조회 (BY-828) — 모두 보기. */
@Service
@RequiredArgsConstructor
public class RankingRecordService {

    static final int MAX_SIZE = 50;

    private static final Set<RankingBoardType> RECORDED_TYPES = EnumSet.of(FOCUS_TIME, FOCUS_RATE, TIME_SLOT);

    private final RankingRecordQueries queries;

    /** 모두 보기 — 내 기록을 (마감 시각, id) 내림차순 커서 페이지로. 달별 묶음은 FE가 한다. */
    @Transactional(readOnly = true)
    public RankingRecordPageResponse list(long userId, Integer rank, RankingBoardType type, String cursor, int size) {
        validate(rank, type, size);
        RecordCursor after = cursor == null ? null : RecordCursor.decode(cursor);
        List<RankingRecordRow> rows = queries.page(userId, rank, type, after, size + 1);
        boolean hasNext = rows.size() > size;
        List<RankingRecordRow> page = hasNext ? rows.subList(0, size) : rows;
        String nextCursor = hasNext
                ? new RecordCursor(page.getLast().closesAt(), page.getLast().id()).encode()
                : null;
        return new RankingRecordPageResponse(page.stream().map(RecordItems::of).toList(), nextCursor);
    }

    private static void validate(Integer rank, RankingBoardType type, int size) {
        if (rank != null && (rank < 1 || rank > Medals.PODIUM)) {
            throw new BadRequestException("rank는 1·2·3 중 하나여야 합니다");
        }
        if (type != null && !RECORDED_TYPES.contains(type)) {
            throw new BadRequestException(type + " 종목에는 마감 기록이 없습니다");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("size는 1~" + MAX_SIZE + "이어야 합니다");
        }
    }
}
```

- [ ] **Step 8: 컨트롤러 작성**

`src/main/java/project/study/ranking/controller/RankingRecordController.java`:

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
import project.study.ranking.dto.RankingRecordPageResponse;
import project.study.ranking.service.RankingRecordService;

@Tag(name = "Ranking")
@RestController
@RequestMapping("/api/rankings/records")
@RequiredArgsConstructor
public class RankingRecordController {

    private final RankingRecordService recordService;

    @Operation(summary = "마감 기록 모두 보기", description = """
                    내가 1·2·3위로 마감한 판을 마감 시각 최신순(같으면 id 내림차순)으로 준다. 달별 묶음은 FE가 한다.

                    - rank(1·2·3)·type(FOCUS_TIME·FOCUS_RATE·TIME_SLOT)으로 거를 수 있다. 범위 밖이면 400
                    - size 기본 20·최대 50. 다음 페이지는 nextCursor를 cursor로 넘긴다(마지막이면 null)
                    - value: 시간 판 초, 집중률 %(소수 1자리)""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(version = "1")
    public RankingRecordPageResponse list(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "순위 1·2·3") @RequestParam(required = false) Integer rank,
            @Parameter(description = "종목 — FOCUS_TIME·FOCUS_RATE·TIME_SLOT") @RequestParam(required = false)
                    RankingBoardType type,
            @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "페이지 크기 1~50", example = "20") @RequestParam(defaultValue = "20") int size) {
        return recordService.list(userId, rank, type, cursor, size);
    }
}
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 10: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/dto/RecordCursor.java \
        src/main/java/project/study/ranking/dto/RankingRecordRow.java \
        src/main/java/project/study/ranking/dto/RankingRecordItem.java \
        src/main/java/project/study/ranking/dto/RankingRecordPageResponse.java \
        src/main/java/project/study/ranking/repository/RankingRecordQueries.java \
        src/main/java/project/study/ranking/service/RecordItems.java \
        src/main/java/project/study/ranking/service/RankingRecordService.java \
        src/main/java/project/study/ranking/controller/RankingRecordController.java \
        src/test/java/project/study/ranking/RecordCursorTest.java \
        src/test/java/project/study/ranking/RankingRecordTestBase.java \
        src/test/java/project/study/ranking/RankingRecordListApiTest.java
git commit -m "feat: 랭킹 마감 기록 목록 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 안 본 기록(마감 모달·04시 보류)과 seen

**Files:**
- Create: `src/main/java/project/study/ranking/dto/MedalCounts.java`
- Create: `src/main/java/project/study/ranking/dto/RankingUnseenResponse.java`
- Create: `src/main/java/project/study/ranking/dto/RankingRecordSeenRequest.java`
- Modify: `src/main/java/project/study/ranking/repository/RankingRecordQueries.java` (`unseen`, `counts`, `markSeen`)
- Modify: `src/main/java/project/study/ranking/service/RankingRecordService.java` (`unseen`, `markSeen`, 보류 상한)
- Modify: `src/main/java/project/study/ranking/controller/RankingRecordController.java` (2개 엔드포인트)
- Create: `src/test/java/project/study/ranking/RankingUnseenApiTest.java`

**Interfaces:**
- Consumes: `RankingCloseRepository.countClosedAt(Collection<String>, Instant)` (Task 1), `TimeSlot.slotDateOf(Instant)` (PR ①), `RankingRecordTestBase` (Task 5)
- Produces:
  - `record MedalCounts(long first, long second, long third)` — `long total()`
  - `record RankingUnseenResponse(long total, List<RankingRecordItem> records)`
  - `record RankingRecordSeenRequest(List<Long> ids)` — `@NotNull @Size(max = 100)`, 원소 `@NotNull`
  - `RankingRecordQueries`: `List<RankingRecordRow> unseen(long userId, Instant closedUpTo)`, `MedalCounts counts(long userId)`, `int markSeen(long userId, Collection<Long> ids, Instant seenAt)`
  - `RankingRecordService`: `RankingUnseenResponse unseen(long userId)`, `void markSeen(long userId, List<Long> ids)`, 패키지 전용 `Instant unseenCutoff(Instant now)`
  - `GET /api/rankings/records/unseen`, `POST /api/rankings/records/seen`(204)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/project/study/ranking/RankingUnseenApiTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 마감 모달 — 안 본 기록의 순서와 04시 보류, 본 것으로 표시 (BY-828). 기준 시각은 2026-10-10(토) 15:00 KST. */
class RankingUnseenApiTest extends RankingRecordTestBase {

    private static final String UNSEEN = "/api/rankings/records/unseen";
    private static final String SEEN = "/api/rankings/records/seen";
    private static final LocalDate THU = LocalDate.of(2026, 10, 8);
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);

    private void assertUnseen(long userId, long... ids) {
        assertThat(get(UNSEEN, userId))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.records[*].id")
                .asArray()
                .containsExactly(Arrays.stream(ids).mapToObj(id -> (Object) (int) id).toArray());
    }

    @Test
    void 순위_일주월_종목_구간_순으로_주고_total은_본_것까지_센_누적_메달_수다() {
        long me = user("me");
        closed("TIME_SLOT:DAILY:NIGHT", FRI, kst(10, 10, 4, 0), false);
        long night = record(me, "TIME_SLOT:DAILY:NIGHT", FRI, kst(10, 10, 4, 0), 1, "1900.0");
        long second = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 2, "5000.0");
        long daily = record(me, "FOCUS_TIME:DAILY", THU, kst(10, 9, 0, 0), 1, "4000.0");
        long weekly = record(me, "FOCUS_RATE:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 1, "90.9");
        long morning = record(me, "TIME_SLOT:DAILY:MORNING", FRI, kst(10, 10, 0, 0), 1, "3000.0");
        long seen = record(me, "FOCUS_TIME:MONTHLY", LocalDate.of(2026, 9, 1), kst(10, 1, 0, 0), 1, "90000.0");
        jdbc.update("UPDATE ranking_record SET seen_at = now() WHERE id = ?", seen);

        assertUnseen(me, daily, morning, night, weekly, second);
        assertThat(get(UNSEEN, me)).bodyJson().hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(6));
    }

    @Test
    void 그날_04시_심야_마감이_확정되기_전에는_00시_마감분을_주지_않는다() {
        long me = user("me");
        long saturday = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 1, "5000.0");
        long friday = record(me, "FOCUS_TIME:DAILY", THU, kst(10, 9, 0, 0), 1, "4000.0");
        closed("TIME_SLOT:DAILY:NIGHT", THU, kst(10, 9, 4, 0), false);

        clock.set(kst(10, 10, 3, 0));
        assertUnseen(me, friday);

        clock.set(kst(10, 10, 4, 0).plusSeconds(30));
        assertUnseen(me, friday);

        closed("TIME_SLOT:DAILY:NIGHT", FRI, kst(10, 10, 4, 0), false);
        assertUnseen(me, friday, saturday);
    }

    @Test
    void 월요일엔_심야_주간_마감까지_표시돼야_00시_마감분을_주고_건너뛴_마감도_센다() {
        long me = user("me");
        long older = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 1, "5000.0");
        long weekly = record(me, "FOCUS_TIME:WEEKLY", LocalDate.of(2026, 10, 5), kst(10, 12, 0, 0), 1, "40000.0");
        clock.set(kst(10, 12, 5, 0));

        closed("TIME_SLOT:DAILY:NIGHT", LocalDate.of(2026, 10, 11), kst(10, 12, 4, 0), false);
        assertUnseen(me, older);

        closed("TIME_SLOT:WEEKLY:NIGHT", LocalDate.of(2026, 10, 5), kst(10, 12, 4, 0), true); // 건너뛴 마감도 보류를 푼다
        assertUnseen(me, older, weekly);
    }

    @Test
    void 본_것으로_표시하면_다시_주지_않고_남의_기록은_건드리지_않는다() {
        long me = user("me");
        long other = user("other");
        closed("TIME_SLOT:DAILY:NIGHT", FRI, kst(10, 10, 4, 0), false);
        long mine = record(me, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 1, "5000.0");
        long theirs = record(other, "FOCUS_TIME:DAILY", FRI, kst(10, 10, 0, 0), 2, "4000.0");

        assertThat(post(SEEN, me, "{\"ids\": [" + mine + ", " + theirs + "]}")).hasStatus(HttpStatus.NO_CONTENT);

        assertUnseen(me);
        assertUnseen(other, theirs);
        assertThat(jdbc.queryForObject("SELECT seen_at FROM ranking_record WHERE id = ?", OffsetDateTime.class, mine)
                        .toInstant())
                .isEqualTo(NOW);
    }

    @Test
    void ids가_없거나_100개를_넘으면_400이고_빈_목록은_아무것도_하지_않는다() {
        long me = user("me");
        String tooMany = LongStream.rangeClosed(1, 101)
                .mapToObj(Long::toString)
                .collect(Collectors.joining(",", "{\"ids\": [", "]}"));

        assertThat(post(SEEN, me, "{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(SEEN, me, "{\"ids\": [null]}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(SEEN, me, tooMany)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(post(SEEN, me, "{\"ids\": []}")).hasStatus(HttpStatus.NO_CONTENT);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.RankingUnseenApiTest"`
Expected: FAIL — `/unseen`·`/seen`이 없어 404(또는 405).

- [ ] **Step 3: DTO 작성**

`src/main/java/project/study/ranking/dto/MedalCounts.java`:

```java
package project.study.ranking.dto;

/** 순위별 메달 수 (BY-828). */
public record MedalCounts(long first, long second, long third) {

    public long total() {
        return first + second + third;
    }
}
```

`src/main/java/project/study/ranking/dto/RankingUnseenResponse.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 마감 모달 (BY-828) — 안 본 기록과 누적 메달 수. */
@Schema(description = "마감 모달 — 안 본 기록과 누적 메달 수")
public record RankingUnseenResponse(
        @Schema(description = "누적 메달 수(본 것 포함)") long total,
        @Schema(description = "안 본 기록 — 순위 → 일·주·월 → 순공·집중률·시간대 순") List<RankingRecordItem> records) {}
```

`src/main/java/project/study/ranking/dto/RankingRecordSeenRequest.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 본 것으로 표시할 기록 (BY-828). */
@Schema(description = "본 것으로 표시할 기록")
public record RankingRecordSeenRequest(
        @Schema(description = "records/unseen에서 받은 기록 id, 최대 100개", example = "[31, 32]")
        @NotNull
        @Size(max = 100)
        List<@NotNull Long> ids) {}
```

- [ ] **Step 4: 쿼리 추가**

`RankingRecordQueries.java` — import에 `java.time.Instant`, `java.util.Collection`, `project.study.ranking.dto.MedalCounts`를 더하고 `page` 뒤에 추가:

```java
    /** 안 본 기록 중 closedUpTo까지(포함) 마감한 것. 순서는 서비스가 정한다. */
    public List<RankingRecordRow> unseen(long userId, Instant closedUpTo) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM ranking_record WHERE user_id = :userId AND seen_at IS NULL AND closes_at <= :closedUpTo")
                .param("userId", userId)
                .param("closedUpTo", closedUpTo.atOffset(ZoneOffset.UTC))
                .query(ROW)
                .list();
    }

    public MedalCounts counts(long userId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE rank = 1) AS first_count,
                               count(*) FILTER (WHERE rank = 2) AS second_count,
                               count(*) FILTER (WHERE rank = 3) AS third_count
                        FROM ranking_record
                        WHERE user_id = :userId""")
                .param("userId", userId)
                .query((rs, i) -> new MedalCounts(
                        rs.getLong("first_count"), rs.getLong("second_count"), rs.getLong("third_count")))
                .single();
    }

    /** 내 기록만 본 것으로 표시한다 — 남의 id·이미 본 id는 그대로 둔다. */
    public int markSeen(long userId, Collection<Long> ids, Instant seenAt) {
        return jdbc.sql("""
                        UPDATE ranking_record SET seen_at = :seenAt
                        WHERE user_id = :userId AND id IN (:ids) AND seen_at IS NULL""")
                .param("seenAt", seenAt.atOffset(ZoneOffset.UTC))
                .param("userId", userId)
                .param("ids", ids)
                .update();
    }
```

- [ ] **Step 5: 서비스에 안 본 기록·seen 추가**

`RankingRecordService.java` — 필드·상수·메서드를 추가한다. 최종 필드는 아래와 같다(`@RequiredArgsConstructor`가 생성자를 만든다):

```java
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime NIGHT_CLOSE = LocalTime.of(4, 0);
    private static final String DAILY_NIGHT = new RankingBoard(TIME_SLOT, RankingPeriod.DAILY, TimeSlot.NIGHT).key();
    private static final String WEEKLY_NIGHT = new RankingBoard(TIME_SLOT, RankingPeriod.WEEKLY, TimeSlot.NIGHT).key();

    /** 마감 모달 순서 — 순위 → 일·주·월 → 순공·집중률·시간대(명세 §4-5) → 구간 → 마감 시각 → id. */
    private static final Comparator<RankingRecordItem> MODAL_ORDER = Comparator.comparingInt(RankingRecordItem::rank)
            .thenComparing(RankingRecordItem::period)
            .thenComparing(RankingRecordItem::type)
            .thenComparing(RankingRecordItem::slot, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RankingRecordItem::closesAt)
            .thenComparingLong(RankingRecordItem::id);

    private final RankingRecordQueries queries;
    private final RankingCloseRepository closes;
    private final Clock clock;
```

`list` 뒤에 추가:

```java
    /**
     * 마감 모달 — 안 본 기록을 모달 순서로. 04시 보류: 가장 최근 04:00 심야 마감(월요일이면 심야 주간까지)이 표시되기 전에는 그 전날
     * 04:00까지 마감된 기록만 준다 — 00시 메달을 그날 04시 심야 메달과 묶어 04시 이후 처음 앱을 열 때 한 번에 보여주기 위해서다.
     */
    @Transactional(readOnly = true)
    public RankingUnseenResponse unseen(long userId) {
        List<RankingRecordItem> records = queries.unseen(userId, unseenCutoff(clock.instant())).stream()
                .map(RecordItems::of)
                .sorted(MODAL_ORDER)
                .toList();
        return new RankingUnseenResponse(queries.counts(userId).total(), records);
    }

    /** 내 기록만 본 것으로 표시한다. 빈 목록이면 아무것도 하지 않는다. */
    @Transactional
    public void markSeen(long userId, List<Long> ids) {
        if (!ids.isEmpty()) {
            queries.markSeen(userId, ids, clock.instant());
        }
    }

    /** 안 본 기록을 줄 마감 시각 상한 — 가장 최근 04:00 심야 마감이 (건너뛴 것 포함) 표시됐으면 그 시각, 아니면 하루 전 04:00. */
    Instant unseenCutoff(Instant now) {
        LocalDate day = TimeSlot.slotDateOf(now);
        List<String> nightKeys =
                day.getDayOfWeek() == DayOfWeek.MONDAY ? List.of(DAILY_NIGHT, WEEKLY_NIGHT) : List.of(DAILY_NIGHT);
        Instant latestNightClose = nightClose(day);
        return closes.countClosedAt(nightKeys, latestNightClose) == nightKeys.size()
                ? latestNightClose
                : nightClose(day.minusDays(1));
    }

    private static Instant nightClose(LocalDate day) {
        return day.atTime(NIGHT_CLOSE).atZone(KST).toInstant();
    }
```

추가 import: `java.time.Clock`, `java.time.DayOfWeek`, `java.time.Instant`, `java.time.LocalDate`, `java.time.LocalTime`, `java.time.ZoneId`,
`java.util.Comparator`, `project.study.ranking.RankingBoard`, `project.study.ranking.RankingPeriod`, `project.study.ranking.dto.RankingRecordItem`,
`project.study.ranking.dto.RankingUnseenResponse`, `project.study.ranking.repository.RankingCloseRepository`, `project.study.studysession.entity.TimeSlot`.
클래스 Javadoc을 `/** 랭킹 마감 기록 조회 (BY-828) — 모두 보기, 마감 모달(04시 보류), 본 것으로 표시. */`로 바꾼다.

- [ ] **Step 6: 컨트롤러에 엔드포인트 추가**

`RankingRecordController.java` — `list` 뒤에 추가:

```java
    @Operation(summary = "안 본 마감 기록 (마감 모달)", description = """
                    아직 보지 않은 메달을 순위 → 일·주·월 → 순공·집중률·시간대 순으로 준다. total은 누적 메달 수(본 것 포함)다.

                    - 04시 보류: 그날 04:00 심야 마감(월요일이면 심야 주간까지)이 확정되기 전(00:00~04:01)에는 그날 00시 마감분을 주지 \
                    않는다 — 04시 이후 처음 앱을 열 때 그날 메달을 한 번에 보여준다
                    - 모달을 띄운 뒤 records/seen으로 본 것으로 표시한다""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/unseen", version = "1")
    public RankingUnseenResponse unseen(@AuthenticationPrincipal Long userId) {
        return recordService.unseen(userId);
    }

    @Operation(summary = "마감 기록 본 것으로 표시", description = "내 기록만 바뀐다 — 남의 id·이미 본 id는 무시한다. ids는 최대 100개")
    @ApiResponse(responseCode = "204", description = "표시 완료")
    @PostMapping(value = "/seen", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@AuthenticationPrincipal Long userId, @Valid @RequestBody RankingRecordSeenRequest request) {
        recordService.markSeen(userId, request.ids());
    }
```

추가 import: `jakarta.validation.Valid`, `org.springframework.http.HttpStatus`, `org.springframework.web.bind.annotation.PostMapping`,
`org.springframework.web.bind.annotation.RequestBody`, `org.springframework.web.bind.annotation.ResponseStatus`,
`project.study.ranking.dto.RankingRecordSeenRequest`, `project.study.ranking.dto.RankingUnseenResponse`.

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 8: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/dto/MedalCounts.java \
        src/main/java/project/study/ranking/dto/RankingUnseenResponse.java \
        src/main/java/project/study/ranking/dto/RankingRecordSeenRequest.java \
        src/main/java/project/study/ranking/repository/RankingRecordQueries.java \
        src/main/java/project/study/ranking/service/RankingRecordService.java \
        src/main/java/project/study/ranking/controller/RankingRecordController.java \
        src/test/java/project/study/ranking/RankingUnseenApiTest.java
git commit -m "feat: 마감 모달용 안 본 기록과 본 것 표시 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 기록 요약 API (메달 버튼·시트)

**Files:**
- Create: `src/main/java/project/study/ranking/dto/RankingBestRow.java`
- Create: `src/main/java/project/study/ranking/dto/RankingRecordSummaryResponse.java`
- Modify: `src/main/java/project/study/ranking/repository/RankingRecordQueries.java` (`best`)
- Create: `src/main/java/project/study/ranking/service/RankingRecordSummaryService.java`
- Modify: `src/main/java/project/study/ranking/controller/RankingRecordController.java` (`summary`)
- Create: `src/test/java/project/study/ranking/service/MedalGapTest.java`
- Create: `src/test/java/project/study/ranking/RankingRecordSummaryApiTest.java`

**Interfaces:**
- Consumes: `StandingsProvider.view(RankingBoard, Window, long userId, Instant now)` → `BoardView.placement()`, `Placement.present()/me()/size()/merged()`, `RankingCalendar.window(..., 0)` (PR ①); `RankingRecordQueries.counts/page` (Task 5·6); `Medals.PODIUM/MIN_TIME_VALUE` (Task 3); `RankingBoard.closable/fromKey` (Task 1)
- Produces:
  - `record RankingBestRow(int rank, String boardKey, LocalDate periodStart)`
  - `record RankingRecordSummaryResponse(long total, long firstCount, long secondCount, long thirdCount, List<RankingRecordItem> recent, Best best, Closest closest)` + 중첩 `Best(int rank, RankingBoardType type, RankingPeriod period, TimeSlot slot, LocalDate periodStart)`, `Closest(RankingBoardType type, RankingPeriod period, TimeSlot slot, long gap)`
  - `Optional<RankingBestRow> RankingRecordQueries.best(long userId)`
  - `RankingRecordSummaryResponse RankingRecordSummaryService.summary(long userId)` — **트랜잭션 없음**
  - 패키지 전용 `static long RankingRecordSummaryService.medalGap(Placement)`
  - `GET /api/rankings/records/summary`

- [ ] **Step 1: 실패하는 테스트 작성 — 메달까지 남은 양(단위)**

`src/test/java/project/study/ranking/service/MedalGapTest.java`:

```java
package project.study.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.RankingEntry;

/** gap = max(0, 3위 값 − 내 값, 1800 − 내 값) — 참가 전이면 내 값 0, 3위가 없으면 3위 값 0 (BY-828 §7.2). */
class MedalGapTest {

    private static final Instant T = Instant.parse("2026-10-10T06:00:00Z");

    private static RankingEntry entry(long userId, double value) {
        return RankingEntry.of(userId, "u" + userId, value, T);
    }

    @Test
    void 삼위_안이고_30분을_넘었으면_0이다() {
        Placement placement = new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000)), 1);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isZero();
    }

    @Test
    void 삼위_밖이면_삼위_값까지_남은_양이다() {
        Placement placement =
                new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000), entry(4, 2500)), 3);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(500);
    }

    @Test
    void 삼위_안이어도_30분이_안_되면_30분까지_남은_양이다() {
        Placement placement = new Placement(List.of(entry(1, 1000)), 0);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(800);
    }

    @Test
    void 참가_전이고_세_명이_안_되면_30분이다() {
        Placement placement = new Placement(List.of(entry(2, 1200)), -1);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(1800);
    }

    @Test
    void 참가_전이면_삼위_값_전부다() {
        Placement placement = new Placement(List.of(entry(1, 5000), entry(2, 4000), entry(3, 3000)), -1);

        assertThat(RankingRecordSummaryService.medalGap(placement)).isEqualTo(3000);
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성 — 요약 API**

`src/test/java/project/study/ranking/RankingRecordSummaryApiTest.java`:

```java
package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import project.study.config.ApiVersionConfig;

/** 메달 버튼·시트 요약 (BY-828). 기준 시각은 2026-10-10(토) 15:00 KST — 지금 구간은 오후다. */
class RankingRecordSummaryApiTest extends RankingRecordTestBase {

    private static final String SUMMARY = "/api/rankings/records/summary";

    @Test
    void 기록이_있으면_순위별_개수와_최근_6개만_주고_best_closest는_null이다() {
        long me = user("me");
        int[] ranks = {1, 1, 2, 2, 2, 3, 1};
        for (int i = 0; i < ranks.length; i++) {
            record(me, "FOCUS_TIME:DAILY", LocalDate.of(2026, 10, 1 + i), kst(10, 2 + i, 0, 0), ranks[i], "3000.0");
        }
        record(user("other"), "FOCUS_TIME:WEEKLY", LocalDate.of(2026, 9, 28), kst(10, 5, 0, 0), 1, "40000.0");

        assertThat(get(SUMMARY, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(7))
                .hasPathSatisfying("$.firstCount", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.secondCount", v -> assertThat(v).isEqualTo(3))
                .hasPathSatisfying("$.thirdCount", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.recent.length()", v -> assertThat(v).isEqualTo(6))
                .hasPathSatisfying("$.recent[0].periodStart", v -> assertThat(v).isEqualTo("2026-10-07"))
                .hasPathSatisfying("$.recent[5].periodStart", v -> assertThat(v).isEqualTo("2026-10-02"))
                .hasPathSatisfying("$.best", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.closest", v -> assertThat(v).isNull());
    }

    @Test
    void 기록이_없으면_역대_최고와_메달에_가장_가까운_판을_준다() {
        long me = user("me");
        session(me, kst(10, 10, 9, 0), 40, 2000); // 오늘 오전 — 오전 일간판엔 나 혼자라 남은 양 0
        session(user("a"), kst(10, 10, 12, 0), 120, 5000); // 오늘 오후 — 순공 판에서 나는 4위(3위까지 1000초)
        session(user("b"), kst(10, 10, 12, 0), 90, 4000);
        session(user("c"), kst(10, 10, 13, 0), 60, 3000);
        best(me, 2, "TIME_SLOT:WEEKLY:EVENING", LocalDate.of(2026, 9, 28));

        assertThat(get(SUMMARY, me))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.total", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.best.rank", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying("$.best.type", v -> assertThat(v).isEqualTo("TIME_SLOT"))
                .hasPathSatisfying("$.best.period", v -> assertThat(v).isEqualTo("WEEKLY"))
                .hasPathSatisfying("$.best.slot", v -> assertThat(v).isEqualTo("EVENING"))
                .hasPathSatisfying("$.best.periodStart", v -> assertThat(v).isEqualTo("2026-09-28"))
                .hasPathSatisfying("$.closest.type", v -> assertThat(v).isEqualTo("TIME_SLOT"))
                .hasPathSatisfying("$.closest.period", v -> assertThat(v).isEqualTo("DAILY"))
                .hasPathSatisfying("$.closest.slot", v -> assertThat(v).isEqualTo("MORNING"))
                .hasPathSatisfying("$.closest.gap", v -> assertThat(v).isEqualTo(0))
                .extractingPath("$.recent")
                .asArray()
                .isEmpty();
    }

    @Test
    void 아무것도_없으면_best는_null이고_closest는_30분_남은_순공_일간판이다() {
        assertThat(get(SUMMARY, user("me")))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.best", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.closest.type", v -> assertThat(v).isEqualTo("FOCUS_TIME"))
                .hasPathSatisfying("$.closest.period", v -> assertThat(v).isEqualTo("DAILY"))
                .hasPathSatisfying("$.closest.slot", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.closest.gap", v -> assertThat(v).isEqualTo(1800));
    }

    @Test
    void 토큰이_없으면_401이다() {
        assertThat(mvc.get().uri(SUMMARY).header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.ranking.service.MedalGapTest" --tests "project.study.ranking.RankingRecordSummaryApiTest"`
Expected: 컴파일 실패 — `RankingRecordSummaryService`가 없다.

- [ ] **Step 4: DTO 작성**

`src/main/java/project/study/ranking/dto/RankingBestRow.java`:

```java
package project.study.ranking.dto;

import java.time.LocalDate;

/** 역대 마감 최고 순위 한 행 (BY-828). */
public record RankingBestRow(int rank, String boardKey, LocalDate periodStart) {}
```

`src/main/java/project/study/ranking/dto/RankingRecordSummaryResponse.java`:

```java
package project.study.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingPeriod;
import project.study.studysession.entity.TimeSlot;

/** 마감 기록 요약 (BY-828) — 메달 버튼·시트. 기록이 없을 때만 역대 최고와 메달에 가장 가까운 판을 준다. */
@Schema(description = "마감 기록 요약 — 메달 버튼·시트")
public record RankingRecordSummaryResponse(
        @Schema(description = "누적 메달 수") long total,
        long firstCount,
        long secondCount,
        long thirdCount,
        @Schema(description = "최근 기록 6개 — 모두 보기와 같은 순서") List<RankingRecordItem> recent,
        @Schema(description = "total이 0일 때만: 역대 마감 최고 순위 — 없으면 null") Best best,
        @Schema(description = "total이 0일 때만: 메달까지 남은 양이 가장 작은 진행 중 시간 판") Closest closest) {

    public record Best(int rank, RankingBoardType type, RankingPeriod period, TimeSlot slot, LocalDate periodStart) {}

    @Schema(description = "gap = max(0, 3위 값 − 내 값, 1800 − 내 값) 초 — 순공 일·주·월, 시간대 일·주 × 5구간 중 가장 작은 것")
    public record Closest(RankingBoardType type, RankingPeriod period, TimeSlot slot, long gap) {}
}
```

- [ ] **Step 5: 개인 최고 쿼리 추가**

`RankingRecordQueries.java` — import에 `java.util.Optional`, `project.study.ranking.dto.RankingBestRow`를 더하고 추가:

```java
    public Optional<RankingBestRow> best(long userId) {
        return jdbc.sql("SELECT rank, board_key, period_start FROM ranking_best WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, i) -> new RankingBestRow(
                        rs.getInt("rank"), rs.getString("board_key"), rs.getObject("period_start", LocalDate.class)))
                .optional();
    }
```

- [ ] **Step 6: 요약 서비스 작성**

`src/main/java/project/study/ranking/service/RankingRecordSummaryService.java`:

```java
package project.study.ranking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.ranking.RankingBoard;
import project.study.ranking.RankingBoardType;
import project.study.ranking.RankingCalendar;
import project.study.ranking.close.Medals;
import project.study.ranking.dto.MedalCounts;
import project.study.ranking.dto.RankingRecordItem;
import project.study.ranking.dto.RankingRecordSummaryResponse;
import project.study.ranking.dto.RankingRecordSummaryResponse.Best;
import project.study.ranking.dto.RankingRecordSummaryResponse.Closest;
import project.study.ranking.engine.Placement;
import project.study.ranking.engine.StandingsProvider;
import project.study.ranking.repository.RankingRecordQueries;

/**
 * 메달 버튼·시트 요약 (BY-828). 트랜잭션을 걸지 않는다 — 가까운 판 계산이 순위표 계산(자체 REPEATABLE_READ 트랜잭션)을 부른다
 * (ADR-0028 결정 8). 기록 조회는 각각 자동 커밋으로 읽는다.
 */
@Service
@RequiredArgsConstructor
public class RankingRecordSummaryService {

    static final int RECENT_SIZE = 6;

    private final RankingRecordQueries queries;
    private final StandingsProvider standingsProvider;
    private final Clock clock;

    /** 기록이 있으면 순위별 개수와 최근 6개, 없으면 역대 최고 순위와 메달에 가장 가까운 진행 중 시간 판. */
    public RankingRecordSummaryResponse summary(long userId) {
        MedalCounts counts = queries.counts(userId);
        if (counts.total() > 0) {
            List<RankingRecordItem> recent = queries.page(userId, null, null, null, RECENT_SIZE).stream()
                    .map(RecordItems::of)
                    .toList();
            return new RankingRecordSummaryResponse(
                    counts.total(), counts.first(), counts.second(), counts.third(), recent, null, null);
        }
        return new RankingRecordSummaryResponse(
                0, 0, 0, 0, List.of(), best(userId), closest(userId, clock.instant()));
    }

    private Best best(long userId) {
        return queries.best(userId)
                .map(row -> {
                    RankingBoard board = RankingBoard.fromKey(row.boardKey());
                    return new Best(row.rank(), board.type(), board.period(), board.slot(), row.periodStart());
                })
                .orElse(null);
    }

    /** 순공 일·주·월과 시간대 일·주 × 5구간의 지금 기간 중 메달까지 남은 양이 가장 작은 판 — 같으면 closable() 순서상 앞 판. */
    private Closest closest(long userId, Instant now) {
        Closest closest = null;
        for (RankingBoard board : RankingBoard.closable()) {
            if (board.type() == RankingBoardType.FOCUS_RATE) {
                continue;
            }
            Placement placement = standingsProvider
                    .view(board, RankingCalendar.window(board, now, 0), userId, now)
                    .placement();
            long gap = medalGap(placement);
            if (closest == null || gap < closest.gap()) {
                closest = new Closest(board.type(), board.period(), board.slot(), gap);
            }
        }
        return closest;
    }

    /** max(0, 3위 값 − 내 값, 1800 − 내 값) — 참가 전이면 내 값 0, 3위가 없으면 3위 값 0. */
    static long medalGap(Placement placement) {
        double mine = placement.present() ? placement.me().value() : 0;
        double third = placement.size() >= Medals.PODIUM
                ? placement.merged().get(Medals.PODIUM - 1).value()
                : 0;
        return Math.round(Math.max(0, Math.max(third - mine, Medals.MIN_TIME_VALUE - mine)));
    }
}
```

- [ ] **Step 7: 컨트롤러에 요약 추가**

`RankingRecordController.java` — 필드에 `private final RankingRecordSummaryService summaryService;`를 추가하고 `list` 앞에 추가:

```java
    @Operation(summary = "마감 기록 요약 (메달 버튼·시트)", description = """
                    누적 메달 수, 순위별 개수, 최근 기록 6개를 준다.

                    - 기록이 없을 때만(total 0) best(역대 마감 최고 순위, 없으면 null)와 closest(메달까지 남은 양이 가장 작은 진행 중 시간 판)를 준다
                    - closest.gap = max(0, 3위 값 − 내 값, 1800 − 내 값) 초. 대상은 순공 일·주·월과 시간대 일·주 × 5구간이다""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping(value = "/summary", version = "1")
    public RankingRecordSummaryResponse summary(@AuthenticationPrincipal Long userId) {
        return summaryService.summary(userId);
    }
```

추가 import: `project.study.ranking.dto.RankingRecordSummaryResponse`, `project.study.ranking.service.RankingRecordSummaryService`.

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.ranking.*"`
Expected: PASS

- [ ] **Step 9: 전체 검증 후 커밋**

Run: `./gradlew spotlessApply && ./gradlew check` → BUILD SUCCESSFUL

```bash
git add src/main/java/project/study/ranking/dto/RankingBestRow.java \
        src/main/java/project/study/ranking/dto/RankingRecordSummaryResponse.java \
        src/main/java/project/study/ranking/repository/RankingRecordQueries.java \
        src/main/java/project/study/ranking/service/RankingRecordSummaryService.java \
        src/main/java/project/study/ranking/controller/RankingRecordController.java \
        src/test/java/project/study/ranking/service/MedalGapTest.java \
        src/test/java/project/study/ranking/RankingRecordSummaryApiTest.java
git commit -m "feat: 메달 버튼·시트용 마감 기록 요약 API를 추가한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: ADR·전체 검증·리뷰·PR

**Files:**
- Create: `docs/adr/0029-ranking-close-records.md`
- Modify: `docs/superpowers/specs/2026-10-10-by828-ranking-design.md` (상태 줄)

- [ ] **Step 1: ADR 작성**

`docs/adr/0029-ranking-close-records.md`:

```markdown
# ADR-0029: 랭킹 마감은 1분 뒤 한 번 확정하고 바꾸지 않는다

- 상태: 승인
- 날짜: 2026-10-11
- 티켓: BY-828 (스토리 BY-819, 짝 BY-827)
- 설계: `docs/superpowers/specs/2026-10-10-by828-ranking-design.md` §5.2·§7.2·§8
- 관련: ADR-0028(랭킹은 조회 때 집계)

## 맥락

기간 판(순공 일·주·월, 집중률 주·월, 시간대 일·주 × 5구간)이 마감되면 1·2·3위에게 메달을 남기고, 마감 모달·메달 시트·"지금까지
최고 순위"에 쓴다. 순위표는 ADR-0028의 엔진이 조회 때 계산하므로 마감 순간의 순위를 따로 고정해 둬야 한다. 자정 직전까지 공부한
사람의 마지막 30초는 자정 뒤 첫 하트비트로 들어온다.

## 결정

1. **마감 1분 뒤에 한 번 확정하고 이후 바꾸지 않는다.** 확정 세션과 진행 중 draft를 함께 읽는다 — 자동 확정은 draft 값을 옮길 뿐이라
   기다릴 필요가 없고, 1분은 자정 뒤 첫 스냅샷(하트비트 30초 + 버퍼 flush 5초 + 네트워크)이 들어오는 시간이다. 늦게 제출한 오프라인
   세션은 확정된 기록을 바꾸지 않는다(`board?offset=-1`은 지금 DB로 다시 계산하므로 다를 수 있다).
2. **draft는 보고된 스냅샷 그대로 나눈다.** 조회 때처럼 집중 중 draft를 계산 시각까지 늘리지 않는다 — 앱 시계가 서버보다 늦으면 늘린
   몫이 마감 전 기간으로 들어간다. 마감 시각 뒤의 몫은 자정·구간 분할로 다음 기간에 들어간다.
3. **매분 도는 스케줄러가 "마감 + 1분이 지났고 마감 표시가 없는 판"을 확정한다.** 재기동으로 정각을 놓쳐도 다음 틱에 따라잡는다.
   마감 뒤 1시간이 넘은 판은 확정하지 않고 건너뛴 것으로 표시한다(`skipped`) — 첫 배포 때 지난 기간에 메달이 소급되지 않게, 04시
   보류(결정 6)가 영영 막히지 않게.
4. **판 하나 = 계산 한 번 + 쓰기 트랜잭션 하나.** 순위표 계산은 자체 `REPEATABLE_READ` 트랜잭션을 열어야 해서(ADR-0028 결정 8) 쓰기
   트랜잭션 밖에서 먼저 끝낸다. 쓰기는 마감 표시(`ranking_close`)를 `ON CONFLICT DO NOTHING`으로 먼저 넣고, 이미 있으면 아무것도 쓰지
   않는다 — 배포 중 태스크 둘이 같은 판을 계산해도 하나만 기록한다.
5. **메달은 1~3위 중 시간 판 30분 이상만 받고, 순위는 당기지 않는다**(2026-10-10 기획 변경). 집중률은 참가 조건이 있어 따로 걸지
   않는다. 참가자 전원의 개인 최고(`ranking_best`, 사용자당 1행)는 더 높은 순위일 때만 바꾸고, 같으면 먼저 것을 둔다.
6. **마감 모달은 그날 04:00 심야 마감이 확정된 뒤에야 00시 마감분을 준다.** 가장 최근 04:00의 심야 일간(월요일이면 심야 주간까지)
   마감 표시가 있으면 그 시각까지, 없으면 하루 전 04:00까지 마감된 안 본 기록만 준다. 00시 메달과 04시 심야 메달을 04시 이후 처음
   앱을 열 때 한 번에 보여주기 위해서다.

## 결과

- V27: `ranking_close`(판·기간당 1행), `ranking_record`(메달), `ranking_best`(개인 최고).
- 첫 배포 때 마감 뒤 1시간이 넘은 판은 모두 건너뛴 것으로 표시되고, Sentry 경고가 판 수만큼 한 번 남는다.
- 기록 API 4개(`records/summary`·`records`·`records/unseen`·`records/seen`). 요약의 "메달에 가장 가까운 판"은 시간 판 13개의 순위표를
  엔진 캐시로 읽는다.
- 탈퇴자는 마감에서 빠지고, 이미 받은 기록은 남지만 본인만 조회한다.
```

- [ ] **Step 2: spec 상태 줄 갱신**

`docs/superpowers/specs/2026-10-10-by828-ranking-design.md` 7번째 줄을 바꾼다:

```markdown
- 상태: PR ① 머지(#84) · PR ② 구현 계획 `docs/superpowers/plans/2026-10-11-by828-ranking-records.md`
```

- [ ] **Step 3: 전체 검증**

Run: `./gradlew spotlessApply && ./gradlew check`
Expected: BUILD SUCCESSFUL (테스트·Spotless·checkstyle·ArchUnit 전부)

- [ ] **Step 4: 커밋**

```bash
git add docs/adr/0029-ranking-close-records.md docs/superpowers/specs/2026-10-10-by828-ranking-design.md
git commit -m "docs: 랭킹 마감 확정 규칙을 ADR로 남긴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: 기동 확인**

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
# 다른 터미널
curl -s localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`, 로그에 Flyway V27 적용. 다음 매분 5초 틱에 `랭킹 마감을 1시간 안에 확정하지 못해 건너뜀` 경고가 판 수만큼
(마감 뒤 1시간 안이면 `랭킹 마감 확정`) 찍히고, 그다음 틱부터는 아무것도 찍히지 않는다. 토큰 없이 `/api/rankings/records/summary`를 부르면 401.

- [ ] **Step 6: 2차 리뷰 (CLAUDE.md 크로스 코드체크)**

```bash
codex exec -s read-only "git diff origin/dev...HEAD 를 리뷰해줘. BY-828 랭킹 마감 배치와 기록 API(PR ②)이고 설계는 docs/superpowers/specs/2026-10-10-by828-ranking-design.md §5.2·§7.2·§8, ADR은 docs/adr/0029-ranking-close-records.md·0028이다. 중복 확정 방지(태스크 둘), 엔진 계산을 트랜잭션 밖에서 부르는지, 메달 30분·순위 안 당김, 개인 최고 upsert, 04시 보류 상한, seen 소유 검증, 커서 페이지 경계를 중점으로 P1/P2/P3로 분류해줘."
```
Expected: P1이 없을 것. P1이 있으면 고치고 Step 3부터 다시.

- [ ] **Step 7: 퀴즈 게이트 (CLAUDE.md 7번)**

사용자에게 구현 코드·흐름 퀴즈 5개를 낸다(예: 마감 계산을 쓰기 트랜잭션 안에서 하면 무슨 일이 일어나는가, 태스크 둘이 같은 판을 확정할 때
어떻게 하나만 남는가, 1시간 창이 없으면 첫 배포 때 무슨 일이 생기는가, 00:30에 마감 모달이 00시 메달을 주지 않는 이유, 마감 계산이 집중 중
보정을 끄는 이유). 못 맞추면 다른 퀴즈를 낸다. 사용자가 생략을 명시하면 건너뛴다.

- [ ] **Step 8: push·PR**

PR 본문은 `.github/pull_request_template.md`의 절 구조를 그대로 두고 절마다 짧게 채워 스크래치패드의 `pr-body.md`에 쓴다
(요약: V27 마감 기록, 마감 스케줄러(1분 뒤·1시간 창·중복 방지), 메달 30분, 개인 최고, 기록 API 4개, ADR-0029 / 테스트: `./gradlew check` 통과 /
배포 메모: 첫 배포 때 지난 판이 건너뜀으로 표시되며 Sentry 경고가 한 번 남는다). attribution 푸터는 넣지 않는다(사용자 규칙).

```bash
gh auth status   # 활성 계정 sangjaekwon 확인
git fetch origin && git ls-tree --name-only origin/dev src/main/resources/db/migration/ | grep V27   # 비어 있어야 한다
git push -u origin feature/BY-819-ranking-records
gh pr create --base dev --title "[feat] BY-828 랭킹 마감 배치와 기록 API" --body-file <스크래치패드>/pr-body.md
```
지라 BY-828은 PR ③이 남아 진행 중으로 둔다.
