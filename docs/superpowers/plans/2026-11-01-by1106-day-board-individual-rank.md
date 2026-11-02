# 일수 판 개인 단위·순차 순위 (BY-1106) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 누적 일수(`TOTAL_DAYS`)·연속 공부(`MAX_STREAK`) 판을 `GET /api/rankings/board` 버전 2에서 개인 단위(`podium` 3명, `around` 5줄)와 순차 순위(1, 2, 3)로 내려준다. 버전 1은 지금 응답(묶음·공동 순위) 그대로 둔다.

**Architecture:** 같은 일수 안의 순서를 정하는 보조 키 `tiebreak`를 `RankingEntry`에 더해 정렬 키를 값↓ → tiebreak↓ → 도달 시각↑ → userId로 바꾼다. 누적 일수 판은 누적 순공, 연속 공부 판은 1분 기준 누적 일수를 싣는다. 순위 매기기(`Placement`)는 공동 순위 번호를 `sharedRanks` 플래그로 떼어 v1 경로만 켜고, 같은 값 묶음 기준(상위 %, 바로 위·아래, 다음 구간)은 일수 판이면 버전과 상관없이 유지한다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL, `JdbcClient`, JUnit 5 + AssertJ, `MockMvcTester`, Testcontainers 2.

**근거 문서:** `docs/adr/0028-ranking-aggregate-on-read.md`(결정 5), `docs/adr/0015-api-versioning-strategy.md`, `docs/superpowers/specs/2026-10-10-by828-ranking-design.md`(§3.3, §7.1 판별 추가 필드).

## Global Constraints

- 새 의존성·마이그레이션·`SecurityConfig` 변경 없음.
- v1(`/board` 헤더 없음·`1`) 응답은 바뀌지 않는다. 단 같은 일수 묶음의 대표가 "먼저 도달한 사람"에서 "tiebreak가 큰 사람"으로 바뀌는 것은 감수한다.
- `/board` v2는 `version = "2"` 새 메서드, v1 메서드는 `@Hidden`. 버전이 정확히 일치해야 하고 미지원 버전(예: 3)은 400. Swagger 기본값은 2.
- `session-gains`는 버전을 올리지 않고 일수 판도 순차 순위로 비교한다.
- `focusSec`를 보조 키로 재사용하지 않는다(집중률 판 순서가 바뀐다).
- checkstyle: 파라미터 7개 이하, 메서드 60줄, 순환 복잡도 10, 파일 400줄. 포맷은 `./gradlew spotlessApply`.
- 실행: `./gradlew test --tests '<클래스>' -Porg.gradle.java.installations.paths=<JDK 25 경로>`. 통합테스트는 Docker 필요.
- 이 작업에서는 커밋·푸시하지 않는다(작업 트리에 변경만 남긴다).

## 변경 파일

| 파일 · 메서드 | 지금 | 바뀜 |
|---|---|---|
| `ranking/engine/RankingEntry` | 8개 컴포넌트, `ORDER` = 값↓ → achievedAt↑ → userId | `long tiebreak`(값 다음 자리) 추가, `ORDER` = 값↓ → tiebreak↓ → achievedAt↑ → userId. `of(...)` 팩토리는 tiebreak 0, 일수 판용 `of(userId, nickname, profileImage, value, tiebreak, achievedAt)` 추가. `advancedTo`·`settledAt`이 tiebreak를 옮긴다 |
| `ranking/engine/Placement` | `tied`면 순위·상위 %·위아래가 모두 공동 순위 기준 | `sharedRanks` 컴포넌트 추가(`tied`일 때만 켜진다). `rankAt`은 `sharedRanks`일 때만 묶음 첫 자리. `topPercent`는 `tied`면 늘 묶음 첫 자리 기준. `above`·`below`·`tiedRankOf`는 그대로 |
| `studysession/dto/RankingDaysRow` | days, achievedAt | `long focusSec` 추가 |
| `studysession/repository/StudySessionRankingQueries.studyDays` | 날짜 수만 | `days` CTE에 `SUM(s.focus_sec)`, 바깥에 `SUM(d.focus_sec)`. 순공 합계에만 `focusFrom` 하한 적용(일수 COUNT는 그대로) |
| `ranking/engine/StandingsCalculator.compute` | 일수 판 tiebreak 없음 | `TOTAL_DAYS` tiebreak = 누적 순공, `MAX_STREAK` tiebreak = `studyDays`(1분 기준) 일수 |
| `ranking/service/RankingBoardService.board` | 일수 판은 늘 묶음 | `board(..., offset)` = v2, `board(..., offset, sharedRanks)`. v1 일수 판만 `withSharedRanks(true)` → podium·around 비움, `startNowRank` = `tiedRankOf(1)`. v2는 개인 줄, `startNowRank` = `size()+1` |
| `ranking/service/BoardExtras.streakCard`·`aroundGroups`·`podiumGroups` | 일수 판이면 묶음, 다음 순위는 공동 순위 | 묶음은 `placement.sharedRanks()`일 때만(아니면 null). `streakCard`는 배치를 받아 가정 줄 tiebreak = 내 누적 일수 + 1, 순위 방식은 배치를 따른다 |
| `ranking/service/RankingSessionGainsService.gain` | 이전 순위에 `withTies(type.tiedRanks())` | 제거 — 전후 모두 순차 순위 |
| `ranking/controller/RankingController.board` | `version = "1"` 하나 | `boardV2`(`version = "2"`) 추가, v1은 `@Hidden` |
| `ranking/dto/RankingBoardResponse` @Schema | 일수 판 공동 순위·묶음 설명 | v2 개인 단위·순차 순위, 묶음은 v1만 |
| `docs/adr/0028`·`docs/superpowers/specs/2026-10-10-by828-ranking-design.md` | 공동 순위 | v2 순차·v1 유지·동률 기준 |

## TDD 순서

한 번에 테스트 하나: Red(실패 확인) → Green(최소 구현) → Refactor(초록일 때만).

### Task 1: 정렬 보조 키 (`RankingEntryTest`)

- [x] Red: 같은 값이면 tiebreak가 큰 쪽이 앞이다 / tiebreak가 같으면(0) 먼저 도달한 쪽이 앞이다.

```java
@Test
void 같은_값이면_보조_값이_큰_쪽이_앞이다() {
    RankingEntry early = RankingEntry.of(1, "a", null, 3, 100, T0);
    RankingEntry more = RankingEntry.of(2, "b", null, 3, 200, T0.plusSeconds(60));
    assertThat(Stream.of(early, more).sorted(RankingEntry.ORDER)).containsExactly(more, early);
}
```

- [x] Green: 컴포넌트·`ORDER`·팩토리 추가, 기존 8인자 호출부(`StandingsCalculator.Totals.toEntry`, `BoardExtras.toEligibility`, `StandingsTest`, `RankingEntryTest`)에 tiebreak 0을 넣는다.

### Task 2: 순차 순위 (`PlacementTest`)

- [x] Red: `tied`이고 `sharedRanks`가 아니면 순위는 1, 2, 3…이고, 상위 %는 묶음 첫 자리 기준, 위·아래는 값이 다른 이웃.

```java
Placement p = new Placement(entries(10, 9, 8, 7, 1, 1, 1, 1, 1, 1), 9, true, false);
assertThat(p.myRank()).isEqualTo(10);
assertThat(p.topPercent()).isEqualTo(50);
assertThat(ranks(p.windowAround(3))).containsExactly(2, 3, 4, 5, 6);
```

- [x] Green: `sharedRanks` 컴포넌트(compact 생성자에서 `sharedRanks &= tied`), `withSharedRanks`, `rankAt`·`topPercent` 수정. 기존 공동 순위 테스트 헬퍼는 `new Placement(entries, myIndex, true, true)`.

### Task 3: 누적 순공 (`StudySessionRankingQueriesTest`)

- [x] Red: `studyDays`의 `focusSec`는 1분 이상 측정 조각 합이고, 오늘 뒤·1분 미만은 빠진다.
- [x] Green: CTE·바깥 쿼리 `SUM`, `RankingDaysRow.focusSec`.

### Task 4: 엔진이 tiebreak를 싣는다 (`StandingsCalculatorTest`·`StandingsCalculatorExcludeTest`)

- [x] Red: 누적 일수 판 tiebreak = 누적 시간 판 값, 연속 공부 판 tiebreak = 1분 기준 누적 일수(5분 미만 날 포함). 제출을 빼면 둘 다 함께 빠진다.
- [x] Green: `compute`의 `TOTAL_DAYS`·`MAX_STREAK` 분기.

```java
case MAX_STREAK -> {
    Map<Long, Integer> days = source.studyDays(window.start(), window.end(), onlyUserId, excludeSubmission).stream()
            .collect(Collectors.toMap(RankingDaysRow::userId, RankingDaysRow::days));
    yield source.maxStreaks(...).stream()
            .map(r -> RankingEntry.of(r.userId(), r.nickname(), r.profileImage(), r.days(),
                    days.getOrDefault(r.userId(), 0), r.achievedAt()))
            .toList();
}
```

### Task 5: v2 응답 (`RankingBoardExtrasTest`)

- [x] Red(하나씩): 누적 일수 v2 — podium 3명·around 5줄 순차 순위, groups null, nextTier 그대로, 같은 일수면 순공 많은 쪽이 앞. 연속 공부 v2 — 같은 일수면 누적 일수 많은 쪽이 앞. 미참가 v2 `startNowRank` = 참가자 + 1.
- [x] Green: 서비스 오버로드·`withSharedRanks`·`BoardExtras` 묶음 조건.
- [x] 기존 일수 판 테스트는 `service.board(..., 0, true)`(v1)로 돌려 그대로 통과. `지금_연속이_최고_기록이면…`은 v2에서 2→3, v1에서 2를 각각 확인.

### Task 6: 세션 뒤 오른 랭킹 (`RankingSessionGainsApiTest`)

- [x] `누적_일수는_공동_순위로_비교한다` → `누적_일수는_순차_순위로_비교한다`: before 5, after 4. 세션당 900초 기준 계산 — a·b·c 3일·2700초, d 2일·1800초(10/2 도달), 나 전 2일·1800초(10/5 도달) → d 다음 5위, 후 3일·2700초(10/10 도달) → a·b·c 다음 4위.
- [x] Green: `gain`의 `withTies` 제거.

### Task 7: 버전 (`RankingBoardApiTest`·`ApiVersionSwaggerDocsTest`)

- [x] Red: version 2는 `podium[0].rank`가 있고 `podiumGroups`가 null, version 1은 `podiumGroups`가 있고 `podium`이 [], version 3은 400. Swagger `/api/rankings/board` 기본값 2.
- [x] Green: `RankingController`에 `boardV2` 추가, v1 `@Hidden`, 설명·@Schema·ADR·spec 문구.

### Task 8: 전체 검증

- [x] `./gradlew spotlessApply` 후 `./gradlew check`.

## 위험

| 위험 | 대응 |
|---|---|
| v1 묶음 대표·`myOrder`가 바뀐다 — 같은 일수 안 순서가 도달 시각에서 tiebreak로 바뀐다 | 설계에서 감수하기로 함. ADR에 적는다 |
| 연속 공부 판이 `studyDays`를 한 번 더 읽는다(전체 계산 시 전체 사용자) | 같은 REPEATABLE_READ 트랜잭션. 전체 순위표는 60초 캐시, 내 줄은 매 요청 한 명만 조회. 규모 전제(천~수천)에서 문제없음 |
| 8인자 보조 생성자가 checkstyle `ParameterNumber`(최대 7)에 걸린다 | 보조 생성자 대신 정식 생성자 호출부(main 2곳·test 5곳)에 tiebreak 0을 넣었다 |
| `session-gains`는 버전이 없어 구 앱도 일수 판 순차 순위를 받는다 | 설계 결정(전후 비교라 "올랐다"는 의미는 같다) |
| 마감 기록(`RankingCloser`)은 일수 판이 없어 영향 없음 | 확인만 |

## 리뷰 반영

- [x] 누적 일수 판 보조 값에 누적 시간 판과 같은 하한(`ALL_TIME_START`)을 건다 — `studyDays(focusFrom, today, …)`, 일수는 하한 없이 센다. 회귀 테스트 Red → Green
- [x] `/board` 헤더 없는 요청이 버전 1 묶음으로 오는지 테스트
- [x] 연속 공부 판 `session-gains`에서 누적 일수(보조 값)만 늘어 오른 사례 테스트
- [x] ADR-0028 캐시 문구 정정(전체 순위표 60초 캐시, 내 줄은 매 요청 조회), spec의 연속 기준 10분 → 5분

## 결과

- `./gradlew check` 통과. 마지막 실행 결과는 보고에 적는다.

