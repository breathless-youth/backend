# 기록창 조회 API 재설계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기록창의 주간/월간 뷰와 세션 바텀시트를 위한 조회 API 2개(기간 집계 `GET /api/stats/period`, 세션 단건 상세 `GET /api/study-sessions/{id}`)를 추가한다.

**Architecture:** 서버는 날짜 구간의 원시 집계만 책임진다("주/월" 판단은 클라). 기간 API는 일별 순공/총공부 버킷 + 기간 총합 + (클라가 넘긴 비교 구간의) 직전 순공 총합을 반환하고, 세션 상세 API는 기존 `StudySessionResponse`를 id로 조회해 그대로 반환한다. 발자국 강도·타임라인 세그먼트·색 매핑은 전부 클라 몫이라 서버 설계에 없다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL, Spring Data JPA, JPQL 생성자 표현식 프로젝션, Jackson 3, JUnit5 + Mockito(단위) + Testcontainers 2 + MockMvcTester(통합).

**Spec:** `docs/superpowers/specs/2026-08-28-by454-record-query-api-design.md`

## Global Constraints

- Spring Boot 4.x — 테스트 스타터는 `spring-boot-starter-webmvc-test`, API 통합테스트는 `MockMvcTester`(AssertJ) 사용, Jackson 3(`tools.jackson`).
- DTO는 Java `record`. 생성자 주입만(`@RequiredArgsConstructor`). 엔티티에 `@Data` 금지.
- 순공시간 필터 임계값은 `StudySessionThresholds.MIN_LIST_FOCUS_SEC`(60초) 재사용 — 새 상수 만들지 않는다.
- 400은 `InvalidSessionException`(extends `BadRequestException`), 404는 `NotFoundException` — `GlobalExceptionHandler`가 이미 매핑한다.
- 조회 스코핑은 기존과 동일하게 `userId` 쿼리 파라미터(JWT 파킹 상태).
- 커밋 컨벤션: `<type>: <설명>`. 커밋 전 `./gradlew check` 통과. 브랜치 `feature/BY-454-기록창-조회-API`.
- 작업 위치: `project.study.studysession` 패키지.

---

## File Structure

**신규 파일**
- `src/main/java/project/study/studysession/dto/DailyStudyStat.java` — 일별 집계 한 줄(프로젝션 겸 응답 버킷). `record(LocalDate date, Long studySec, Long focusSec)`.
- `src/main/java/project/study/studysession/dto/StudyPeriodStatsResponse.java` — 기간 조회 응답.
- `src/test/java/project/study/studysession/StudySessionPeriodRepositoryTest.java` — 집계 쿼리 통합테스트.
- `src/test/java/project/study/studysession/service/StudySessionPeriodServiceTest.java` — 기간 서비스 단위테스트.
- `src/test/java/project/study/studysession/StudySessionPeriodApiTest.java` — 기간 API 통합테스트.
- `src/test/java/project/study/studysession/service/StudySessionDetailServiceTest.java` — 세션 상세 서비스 단위테스트.
- `src/test/java/project/study/studysession/StudySessionDetailApiTest.java` — 세션 상세 API 통합테스트.

**수정 파일**
- `src/main/java/project/study/studysession/repository/StudySessionRepository.java` — `findDailyStudyStats(...)`, `findByIdAndUserId(...)` 추가.
- `src/main/java/project/study/studysession/service/StudySessionService.java` — `periodStats(...)`, `findById(...)`, `validatePeriod(...)` 추가.
- `src/main/java/project/study/studysession/controller/StudySessionStatsController.java` — `GET /api/stats/period` 추가.
- `src/main/java/project/study/studysession/controller/StudySessionController.java` — `GET /api/study-sessions/{id}` 추가.

---

### Task 1: 일별 집계 쿼리 (`findDailyStudyStats`) + `DailyStudyStat` 프로젝션

기간 안에서 statDate별로 총공부/순공을 합산하는 group-by 쿼리. `focusSec >= 60`인 세션만 포함하고, 기록 있는 날만 반환한다(빈 날 채우기는 Task 2 서비스가 담당).

**Files:**
- Create: `src/main/java/project/study/studysession/dto/DailyStudyStat.java`
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRepository.java`
- Test: `src/test/java/project/study/studysession/StudySessionPeriodRepositoryTest.java`

**Interfaces:**
- Produces: `record DailyStudyStat(LocalDate date, Long studySec, Long focusSec)`; `List<DailyStudyStat> StudySessionRepository.findDailyStudyStats(Long userId, LocalDate from, LocalDate to, int minFocusSec)` — statDate 오름차순, 기록 있는 날만.

- [ ] **Step 1: `DailyStudyStat` 레코드 생성**

`src/main/java/project/study/studysession/dto/DailyStudyStat.java`:
```java
package project.study.studysession.dto;

import java.time.LocalDate;

/** period 조회의 일별 집계 한 줄 — group-by 프로젝션이자 응답 버킷으로 재사용한다. sum() 결과가 Long이라 박싱 타입을 쓴다. */
public record DailyStudyStat(LocalDate date, Long studySec, Long focusSec) {}
```

- [ ] **Step 2: 실패하는 통합테스트 작성**

`src/test/java/project/study/studysession/StudySessionPeriodRepositoryTest.java`:
```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StudySessionPeriodRepositoryTest {

    @Autowired
    private StudySessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private void save(LocalDate date, Instant startedAt, int studySec, int focusSec) {
        repository.save(new StudySession(
                userId, date, startedAt, startedAt.plusSeconds(studySec), studySec, focusSec, List.of()));
    }

    @Test
    void 일별로_총공부와_순공을_합산하고_짧은세션은_제외한다() {
        LocalDate d1 = LocalDate.of(2026, 8, 24);
        LocalDate d2 = LocalDate.of(2026, 8, 25);
        Instant base = d1.atStartOfDay(ZoneOffset.UTC).toInstant();
        save(d1, base, 3600, 3000);
        save(d1, base.plusSeconds(7200), 1800, 1500); // 같은 날 두 번째
        save(d2, base.plusSeconds(90000), 1200, 1000);
        save(d2, base.plusSeconds(100000), 120, 30); // focusSec 30 < 60 → 제외

        List<DailyStudyStat> stats = repository.findDailyStudyStats(userId, d1, d2, 60);

        assertThat(stats).containsExactly(
                new DailyStudyStat(d1, 5400L, 4500L), new DailyStudyStat(d2, 1200L, 1000L));
    }

    @Test
    void 기록없는_날은_행이_없다() {
        LocalDate d1 = LocalDate.of(2026, 8, 24);
        save(d1, d1.atStartOfDay(ZoneOffset.UTC).toInstant(), 3600, 3000);

        List<DailyStudyStat> stats = repository.findDailyStudyStats(userId, d1, d1.plusDays(3), 60);

        assertThat(stats).extracting(DailyStudyStat::date).containsExactly(d1);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionPeriodRepositoryTest"`
Expected: 컴파일 실패 — `findDailyStudyStats` 미정의.

- [ ] **Step 4: repository에 쿼리 추가**

`StudySessionRepository.java`에 import 추가: `import project.study.studysession.dto.DailyStudyStat;`
그리고 인터페이스에 메서드 추가:
```java
    // period 조회용 — 기간 안 statDate별 총공부/순공 합계 (focusSec >= minFocusSec 세션만, 기록 있는 날만, 오름차순).
    // 빈 날 채우기·직전 기간 합산은 서비스가 담당한다
    @Query("""
            select new project.study.studysession.dto.DailyStudyStat(s.statDate, sum(s.studySec), sum(s.focusSec))
            from StudySession s
            where s.userId = :userId and s.statDate between :from and :to and s.focusSec >= :minFocusSec
            group by s.statDate
            order by s.statDate""")
    List<DailyStudyStat> findDailyStudyStats(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("minFocusSec") int minFocusSec);
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionPeriodRepositoryTest"`
Expected: PASS (2건)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/studysession/dto/DailyStudyStat.java \
        src/main/java/project/study/studysession/repository/StudySessionRepository.java \
        src/test/java/project/study/studysession/StudySessionPeriodRepositoryTest.java
git commit -m "feat: 기간 일별 집계 쿼리 추가 (BY-454)"
```

---

### Task 2: 기간 서비스 (`periodStats`) + `StudyPeriodStatsResponse`

집계 행을 받아 `from~to` 모든 날짜를 0으로 채우고, 총합과 (compare 지정 시) 직전 순공 총합을 계산한다. 입력 검증 포함.

**Files:**
- Create: `src/main/java/project/study/studysession/dto/StudyPeriodStatsResponse.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java`
- Test: `src/test/java/project/study/studysession/service/StudySessionPeriodServiceTest.java`

**Interfaces:**
- Consumes: `StudySessionRepository.findDailyStudyStats(...)` (Task 1), `DailyStudyStat`.
- Produces: `record StudyPeriodStatsResponse(LocalDate from, LocalDate to, Long totalStudySec, Long totalFocusSec, Long previousTotalFocusSec, List<DailyStudyStat> dailyFocusSec)`; `StudyPeriodStatsResponse StudySessionService.periodStats(Long userId, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo)`.

- [ ] **Step 1: `StudyPeriodStatsResponse` 레코드 생성**

`src/main/java/project/study/studysession/dto/StudyPeriodStatsResponse.java`:
```java
package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record StudyPeriodStatsResponse(
        @Schema(description = "조회 구간 시작일(포함)", example = "2026-08-24") LocalDate from,
        @Schema(description = "조회 구간 종료일(포함)", example = "2026-08-30") LocalDate to,
        @Schema(description = "기간 총 공부 시간(초) 합계", example = "47700") Long totalStudySec,
        @Schema(description = "기간 순공 시간(초) 합계", example = "41040") Long totalFocusSec,
        @Schema(description = "직전 비교 구간(compareFrom~compareTo)의 순공 합계(초). compare 미지정 시 null", example = "33840")
                Long previousTotalFocusSec,
        @Schema(description = "from~to 모든 날짜의 일별 집계 — 공부 없는 날도 0으로 채워 오름차순으로 담긴다")
                List<DailyStudyStat> dailyFocusSec) {}
```

- [ ] **Step 2: 실패하는 단위테스트 작성**

`src/test/java/project/study/studysession/service/StudySessionPeriodServiceTest.java`:
```java
package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionPeriodServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    @Test
    void 기록없는_날을_0으로_채우고_총합을_계산한다() {
        LocalDate from = LocalDate.of(2026, 8, 24);
        LocalDate to = LocalDate.of(2026, 8, 26);
        when(studySessionRepository.findDailyStudyStats(1L, from, to, 60))
                .thenReturn(List.of(new DailyStudyStat(LocalDate.of(2026, 8, 25), 1800L, 1500L)));

        StudyPeriodStatsResponse response = service.periodStats(1L, from, to, null, null);

        assertThat(response.dailyFocusSec()).containsExactly(
                new DailyStudyStat(from, 0L, 0L),
                new DailyStudyStat(LocalDate.of(2026, 8, 25), 1800L, 1500L),
                new DailyStudyStat(to, 0L, 0L));
        assertThat(response.totalStudySec()).isEqualTo(1800L);
        assertThat(response.totalFocusSec()).isEqualTo(1500L);
        assertThat(response.previousTotalFocusSec()).isNull();
    }

    @Test
    void compare가_있으면_직전_구간_순공합을_채운다() {
        LocalDate from = LocalDate.of(2026, 8, 24);
        LocalDate to = LocalDate.of(2026, 8, 30);
        LocalDate cFrom = LocalDate.of(2026, 8, 17);
        LocalDate cTo = LocalDate.of(2026, 8, 23);
        when(studySessionRepository.findDailyStudyStats(1L, from, to, 60)).thenReturn(List.of());
        when(studySessionRepository.findDailyStudyStats(1L, cFrom, cTo, 60))
                .thenReturn(List.of(
                        new DailyStudyStat(cFrom, 5000L, 4000L), new DailyStudyStat(cTo, 3000L, 2000L)));

        StudyPeriodStatsResponse response = service.periodStats(1L, from, to, cFrom, cTo);

        assertThat(response.previousTotalFocusSec()).isEqualTo(6000L);
        assertThat(response.dailyFocusSec()).hasSize(7);
    }

    @Test
    void from이_to보다_이후면_400() {
        assertThatThrownBy(() -> service.periodStats(
                        1L, LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 24), null, null))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void compare를_한쪽만_지정하면_400() {
        assertThatThrownBy(() -> service.periodStats(
                        1L, LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 17), null))
                .isInstanceOf(InvalidSessionException.class);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.service.StudySessionPeriodServiceTest"`
Expected: 컴파일 실패 — `periodStats` 미정의.

- [ ] **Step 4: 서비스 메서드 구현**

`StudySessionService.java` 상단에 import 추가(없는 것만):
```java
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.dto.StudyPeriodStatsResponse;
```
클래스 상수 근처에 추가:
```java
    private static final long MAX_PERIOD_DAYS = 366;
```
`streak(...)` 메서드 근처(같은 조회 계열)에서 메서드 추가:
```java
    @Transactional(readOnly = true)
    public StudyPeriodStatsResponse periodStats(
            Long userId, LocalDate from, LocalDate to, LocalDate compareFrom, LocalDate compareTo) {
        validatePeriod(from, to);
        validateRange(compareFrom, compareTo); // compare는 둘 다 있거나 둘 다 없어야 하고, from<=to (기존 규칙 재사용)

        Map<LocalDate, DailyStudyStat> byDate =
                studySessionRepository.findDailyStudyStats(userId, from, to, MIN_LIST_FOCUS_SEC).stream()
                        .collect(Collectors.toMap(DailyStudyStat::date, Function.identity()));

        List<DailyStudyStat> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            daily.add(byDate.getOrDefault(d, new DailyStudyStat(d, 0L, 0L)));
        }
        long totalStudySec = daily.stream().mapToLong(DailyStudyStat::studySec).sum();
        long totalFocusSec = daily.stream().mapToLong(DailyStudyStat::focusSec).sum();

        Long previousTotalFocusSec = null;
        if (compareFrom != null) {
            previousTotalFocusSec =
                    studySessionRepository.findDailyStudyStats(userId, compareFrom, compareTo, MIN_LIST_FOCUS_SEC).stream()
                            .mapToLong(DailyStudyStat::focusSec)
                            .sum();
        }
        return new StudyPeriodStatsResponse(from, to, totalStudySec, totalFocusSec, previousTotalFocusSec, daily);
    }

    /** from/to는 필수이고 from<=to, 범위는 최대 MAX_PERIOD_DAYS일. */
    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidSessionException("from은 to보다 이후일 수 없습니다");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw new InvalidSessionException("조회 범위가 너무 넓습니다 (최대 " + MAX_PERIOD_DAYS + "일)");
        }
    }
```
> 참고: `validateRange`(from/to 둘 다-또는-둘 다 아님 + 순서)는 이미 존재하며 compare 검증에 재사용한다. `MIN_LIST_FOCUS_SEC`, `Map`, `LocalDate`는 이미 import되어 있다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.service.StudySessionPeriodServiceTest"`
Expected: PASS (4건)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/studysession/dto/StudyPeriodStatsResponse.java \
        src/main/java/project/study/studysession/service/StudySessionService.java \
        src/test/java/project/study/studysession/service/StudySessionPeriodServiceTest.java
git commit -m "feat: 기간 집계 조회 서비스 추가 (BY-454)"
```

---

### Task 3: 기간 조회 엔드포인트 (`GET /api/stats/period`)

**Files:**
- Modify: `src/main/java/project/study/studysession/controller/StudySessionStatsController.java`
- Test: `src/test/java/project/study/studysession/StudySessionPeriodApiTest.java`

**Interfaces:**
- Consumes: `StudySessionService.periodStats(...)` (Task 2).
- Produces: `GET /api/stats/period?userId&from&to&compareFrom&compareTo` → `StudyPeriodStatsResponse` (200). 검증 실패 400.

- [ ] **Step 1: 실패하는 통합테스트 작성**

`src/test/java/project/study/studysession/StudySessionPeriodApiTest.java`:
```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionPeriodApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudySessionRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    // API 테스트는 @Transactional이 아니라 save()가 커밋된다 — userId가 테스트마다 유니크라 격리는 유지된다
    private void save(LocalDate statDate, String startedAt, int studySec, int focusSec) {
        Instant start = Instant.parse(startedAt);
        repository.save(new StudySession(
                userId, statDate, start, start.plusSeconds(studySec), studySec, focusSec, List.of()));
    }

    @Test
    void 기간_일별집계와_총합_직전기간을_반환한다() throws Exception {
        save(LocalDate.of(2026, 8, 25), "2026-08-25T01:00:00Z", 1800, 1500);
        save(LocalDate.of(2026, 8, 27), "2026-08-27T01:00:00Z", 3600, 3000);
        save(LocalDate.of(2026, 8, 20), "2026-08-20T01:00:00Z", 1200, 1000); // 지난주(compare)

        MvcTestResult result = mvc.get()
                .uri("/api/stats/period")
                .param("userId", String.valueOf(userId))
                .param("from", "2026-08-24")
                .param("to", "2026-08-30")
                .param("compareFrom", "2026-08-17")
                .param("compareTo", "2026-08-23")
                .exchange();

        assertThat(result).hasStatusOk();
        StudyPeriodStatsResponse body =
                objectMapper.readValue(result.getResponse().getContentAsString(), StudyPeriodStatsResponse.class);
        assertThat(body.dailyFocusSec()).hasSize(7); // 월~일 7일 전부
        assertThat(body.totalFocusSec()).isEqualTo(4500L);
        assertThat(body.totalStudySec()).isEqualTo(5400L);
        assertThat(body.previousTotalFocusSec()).isEqualTo(1000L);
    }

    @Test
    void from이_to보다_이후면_400() {
        assertThat(mvc.get()
                        .uri("/api/stats/period")
                        .param("userId", String.valueOf(userId))
                        .param("from", "2026-08-30")
                        .param("to", "2026-08-24")
                        .exchange())
                .hasStatus4xxClientError();
    }

    @Test
    void compare를_한쪽만_지정하면_400() {
        assertThat(mvc.get()
                        .uri("/api/stats/period")
                        .param("userId", String.valueOf(userId))
                        .param("from", "2026-08-24")
                        .param("to", "2026-08-30")
                        .param("compareFrom", "2026-08-17")
                        .exchange())
                .hasStatus4xxClientError();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionPeriodApiTest"`
Expected: FAIL — `/api/stats/period` 404(엔드포인트 없음).

- [ ] **Step 3: 컨트롤러에 엔드포인트 추가**

`StudySessionStatsController.java`에 import 추가: `import project.study.studysession.dto.StudyPeriodStatsResponse;`
`streak(...)` 위/아래에 메서드 추가:
```java
    @Operation(
            summary = "기간 집계 조회 (주간/월간)",
            description =
                    """
                    from~to 구간의 일별 순공/총공부 집계와 기간 총합을 반환한다 (주간 막대·월간 달력용). \
                    dailyFocusSec는 from~to 모든 날짜를 담으며 공부 없는 날은 0이다 (순공 1분 미만 세션은 집계 제외). \
                    compareFrom/compareTo를 함께 주면 그 구간 순공 합을 previousTotalFocusSec에 담는다(증감 비교용) — \
                    미지정 시 null. from>to, compare 한쪽만 지정, 366일 초과 범위는 400.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 일별 집계 + 기간 총합 + (선택) 직전 기간 순공 합")
    @GetMapping("/period")
    public StudyPeriodStatsResponse period(
            @Parameter(description = "조회할 유저 ID", example = "1") @RequestParam Long userId,
            @Parameter(description = "구간 시작일(ISO-8601, 포함)", example = "2026-08-24")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = "구간 종료일(ISO-8601, 포함)", example = "2026-08-30")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = "비교 구간 시작일 — compareTo와 함께 지정", example = "2026-08-17")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate compareFrom,
            @Parameter(description = "비교 구간 종료일 — compareFrom과 함께 지정", example = "2026-08-23")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate compareTo) {
        return studySessionService.periodStats(userId, from, to, compareFrom, compareTo);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionPeriodApiTest"`
Expected: PASS (3건)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/project/study/studysession/controller/StudySessionStatsController.java \
        src/test/java/project/study/studysession/StudySessionPeriodApiTest.java
git commit -m "feat: 기간 집계 조회 엔드포인트 추가 (BY-454)"
```

---

### Task 4: 세션 단건 상세 서비스 (`findById`) + repo `findByIdAndUserId`

id로 세션을 조회하되 소유자(userId)가 맞아야 한다. 없거나 남의 것이면 404. 응답은 기존 `StudySessionResponse`(events 원시 구간 포함) 재사용.

**Files:**
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRepository.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java`
- Test: `src/test/java/project/study/studysession/service/StudySessionDetailServiceTest.java`

**Interfaces:**
- Produces: `Optional<StudySession> StudySessionRepository.findByIdAndUserId(Long id, Long userId)`; `StudySessionResponse StudySessionService.findById(Long userId, Long id)` — 없으면 `NotFoundException`.

- [ ] **Step 1: 실패하는 단위테스트 작성**

`src/test/java/project/study/studysession/service/StudySessionDetailServiceTest.java`:
```java
package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.common.NotFoundException;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionDetailServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    @Test
    void 세션을_이벤트구간까지_반환한다() {
        Instant start = Instant.parse("2026-08-27T00:12:00Z");
        Instant end = Instant.parse("2026-08-27T01:36:00Z");
        StudySession session = new StudySession(
                1L,
                LocalDate.of(2026, 8, 27),
                start,
                end,
                5040,
                4080,
                List.of(new StatusEvent(
                        EventStatus.PHONE, Instant.parse("2026-08-27T00:34:00Z"), Instant.parse("2026-08-27T00:41:00Z"))));
        when(studySessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));

        StudySessionResponse response = service.findById(1L, 10L);

        assertThat(response.statDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(response.focusRate()).isEqualTo(81.0); // 4080/5040*100 → 81.0
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).status()).isEqualTo(EventStatus.PHONE);
    }

    @Test
    void 없거나_남의세션이면_404() {
        when(studySessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L, 99L)).isInstanceOf(NotFoundException.class);
    }
}
```
> 참고: `StatusEventResponse`에 `status()` 접근자가 있음을 전제한다(기존 DTO). `focusRate` 81.0은 `Math.round(4080*1000.0/5040)/10.0` 결과.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.service.StudySessionDetailServiceTest"`
Expected: 컴파일 실패 — `findByIdAndUserId`/`findById` 미정의.

- [ ] **Step 3: repository 메서드 추가**

`StudySessionRepository.java`에 import 추가: `import java.util.Optional;`
인터페이스에 추가:
```java
    // 세션 단건 상세 조회 — 소유자(userId)가 맞는 세션만. 없거나 남의 것이면 empty → 서비스가 404로 변환
    Optional<StudySession> findByIdAndUserId(Long id, Long userId);
```

- [ ] **Step 4: 서비스 메서드 추가**

`StudySessionService.java`, `periodStats(...)` 근처에 추가(`NotFoundException`은 이미 import됨):
```java
    @Transactional(readOnly = true)
    public StudySessionResponse findById(Long userId, Long id) {
        StudySession session = studySessionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다"));
        return toResponse(session);
    }
```
> `toResponse(...)`는 이미 존재하는 private 메서드다 — focusRate를 계산해 `StudySessionResponse.from`으로 변환한다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.service.StudySessionDetailServiceTest"`
Expected: PASS (2건)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/studysession/repository/StudySessionRepository.java \
        src/main/java/project/study/studysession/service/StudySessionService.java \
        src/test/java/project/study/studysession/service/StudySessionDetailServiceTest.java
git commit -m "feat: 세션 단건 상세 조회 서비스 추가 (BY-454)"
```

---

### Task 5: 세션 단건 상세 엔드포인트 (`GET /api/study-sessions/{id}`)

**Files:**
- Modify: `src/main/java/project/study/studysession/controller/StudySessionController.java`
- Test: `src/test/java/project/study/studysession/StudySessionDetailApiTest.java`

**Interfaces:**
- Consumes: `StudySessionService.findById(Long userId, Long id)` (Task 4).
- Produces: `GET /api/study-sessions/{id}?userId` → `StudySessionResponse` (200). 없거나 남의 세션 404.

- [ ] **Step 1: 실패하는 통합테스트 작성**

`src/test/java/project/study/studysession/StudySessionDetailApiTest.java`:
```java
package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionDetailApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudySessionRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private Long sessionId;

    @BeforeEach
    void seed() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
        // save()가 events를 CascadeType.ALL로 함께 저장한다 — id는 저장 후 엔티티에서 읽는다
        StudySession saved = repository.save(new StudySession(
                userId,
                LocalDate.of(2026, 8, 27),
                Instant.parse("2026-08-27T00:12:00Z"),
                Instant.parse("2026-08-27T01:36:00Z"),
                5040,
                4080,
                List.of(new StatusEvent(
                        EventStatus.PHONE,
                        Instant.parse("2026-08-27T00:34:00Z"),
                        Instant.parse("2026-08-27T00:41:00Z")))));
        sessionId = saved.getId();
    }

    @Test
    void 세션_상세를_이벤트구간까지_반환한다() throws Exception {
        MvcTestResult result = mvc.get()
                .uri("/api/study-sessions/{id}", sessionId)
                .param("userId", String.valueOf(userId))
                .exchange();

        assertThat(result).hasStatusOk();
        StudySessionResponse body =
                objectMapper.readValue(result.getResponse().getContentAsString(), StudySessionResponse.class);
        assertThat(body.id()).isEqualTo(sessionId);
        assertThat(body.focusSec()).isEqualTo(4080);
        assertThat(body.events()).hasSize(1);
    }

    @Test
    void 남의_세션이면_404() {
        Long other = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "other-" + UUID.randomUUID());

        assertThat(mvc.get()
                        .uri("/api/study-sessions/{id}", sessionId)
                        .param("userId", String.valueOf(other))
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void 없는_세션이면_404() {
        assertThat(mvc.get()
                        .uri("/api/study-sessions/{id}", 999999)
                        .param("userId", String.valueOf(userId))
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionDetailApiTest"`
Expected: FAIL — `GET /api/study-sessions/{id}` 미구현.

- [ ] **Step 3: 컨트롤러에 엔드포인트 추가**

`StudySessionController.java`에 import 추가(없는 것만):
```java
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
```
`create(...)` 근처에 메서드 추가:
```java
    @Operation(
            summary = "세션 단건 상세 조회",
            description = "세션 id로 상세를 조회한다(세션 바텀시트용). events에 비공부 상태 구간(status·시각)이 원시로 담긴다. "
                    + "userId는 소유권 검증용 — 없거나 남의 세션이면 404.")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 세션 상세 + 이벤트 구간")
    @GetMapping("/{id}")
    public StudySessionResponse detail(
            @Parameter(description = "세션 ID", example = "10") @PathVariable Long id,
            @Parameter(description = "소유권 검증용 유저 ID", example = "1") @RequestParam Long userId) {
        return studySessionService.findById(userId, id);
    }
```
> 참고: `/{id}`는 `ActiveStudySessionController`의 `/active`(리터럴 경로)와 충돌하지 않는다 — Spring이 리터럴을 패턴보다 우선 매칭한다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.StudySessionDetailApiTest"`
Expected: PASS (3건)

- [ ] **Step 5: 전체 검증**

Run: `./gradlew check`
Expected: 전체 테스트 + 포맷(spotless) + ArchUnit + 린트 통과. 포맷 실패 시 `./gradlew spotlessApply` 후 재실행.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/project/study/studysession/controller/StudySessionController.java \
        src/test/java/project/study/studysession/StudySessionDetailApiTest.java
git commit -m "feat: 세션 단건 상세 조회 엔드포인트 추가 (BY-454)"
```

---

## 구현 후 (플랜 밖)

- **퀴즈 게이트**(CLAUDE.md 규칙 7): 커밋 후 최종 병합 전, 구현 코드·흐름 퀴즈 5개 통과.
- **크로스 코드체크**: `/codex review`로 2차 리뷰(P1 발견 시 FAIL 게이트).
- PR 생성: 제목 `[feat] BY-454 기록창 조회 API 재설계`.
