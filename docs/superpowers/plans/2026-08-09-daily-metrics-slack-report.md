# 일일 지표 Slack 리포트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매일 오전 10시(KST) 서비스 핵심 지표 4개를 Slack 채널로 자동 발송한다.

**Architecture:** `project.study.metrics` 패키지를 신설해 스케줄러 → 리포트 서비스 → Slack 발송기로 이어지는 단방향 흐름을 만든다. 지표는 각자 소유 도메인(`user`, `studysession`)의 서비스에서 가져와 `metrics`가 조립만 한다. 하루 1회 발송은 발송 이력 테이블의 PK 충돌(`ON CONFLICT DO NOTHING`)로 DB가 보장한다.

**Tech Stack:** Spring Boot 4.1, Java 25, PostgreSQL, Flyway, Spring Data JPA, RestClient, JUnit 5 + Mockito + AssertJ, Testcontainers 2.0

## Global Constraints

- **Spring Boot 4.x다.** 3.x 지식과 다르다 — 스타터는 `spring-boot-starter-webmvc`, Jackson은 `tools.jackson` 패키지, Testcontainers는 `org.testcontainers.postgresql.PostgreSQLContainer`.
- **새 의존성을 추가하지 않는다.** 필요한 것은 모두 기존 의존성에 있다. `RestClient.Builder` 빈은 `StudyApplication.java:21`에 이미 등록돼 있다.
- **DB 스키마는 Flyway로만 변경한다.** `ddl-auto`는 `validate` 고정이라 엔티티와 마이그레이션이 정확히 일치해야 한다. 불일치 시 기동 실패한다.
- **DTO는 Java record.** 엔티티에 `@Data` 금지(`@Getter` + 명시적 생성자).
- **생성자 주입만.** `@Autowired` 필드 주입 금지, Lombok `@RequiredArgsConstructor` 사용. ArchUnit `noFieldInjection` 규칙이 강제한다.
- **로거는 Lombok `@Slf4j`** (`DevDataSeeder.java:34` 참고).
- **테스트 메서드명은 한글 스네이크 케이스** (`새_기기_등록은_유저를_생성하고_isNew가_true다`). 단언은 AssertJ.
- **임계값 `600`을 새로 정의하지 않는다.** `StudySessionService.MIN_STREAK_FOCUS_SEC`이 이미 private 상수로 존재한다(ADR-0009). 이 값이 계속 한 곳에만 존재하도록 `StudySessionService`가 조회 메서드를 노출한다.
- **집계 기준일은 "KST 어제"** (`anchorDate`). 헤비유저 구간은 `[anchorDate - 6일, anchorDate]`로 어제를 포함한 7일이다.
- 각 태스크 커밋 전 `./gradlew check` 통과. 포맷은 `./gradlew spotlessApply`로 자동 수정.
- 커밋 메시지는 Conventional Commits (`<type>: <설명>`).

## File Structure

**신규 생성**

| 파일 | 책임 |
|---|---|
| `src/main/resources/db/migration/V8__daily_report_log.sql` | 발송 이력 테이블 |
| `src/main/java/project/study/metrics/entity/DailyReportLog.java` | 발송 이력 엔티티 |
| `src/main/java/project/study/metrics/repository/DailyReportLogRepository.java` | 날짜 선점(`ON CONFLICT DO NOTHING`) |
| `src/main/java/project/study/metrics/dto/DailyReport.java` | 지표 4개 + Slack 메시지 포맷 |
| `src/main/java/project/study/metrics/slack/SlackNotifier.java` | 발송 인터페이스 |
| `src/main/java/project/study/metrics/slack/SlackWebhookNotifier.java` | Webhook 구현 |
| `src/main/java/project/study/metrics/service/DailyReportService.java` | 선점 → 수집 → 발송 조율 |
| `src/main/java/project/study/metrics/scheduler/DailyReportScheduler.java` | 시각 트리거 |
| `src/main/java/project/study/config/SchedulingConfig.java` | `@EnableScheduling` |
| `src/main/java/project/study/studysession/dto/HeavyUser.java` | 헤비유저 조회 결과 |

**수정**

| 파일 | 변경 |
|---|---|
| `src/main/java/project/study/studysession/repository/StudySessionRepository.java` | 헤비유저 조회 + 세션 수 카운트 쿼리 추가 |
| `src/main/java/project/study/studysession/service/StudySessionService.java` | `findHeavyUsers`, `countQualifyingSessionsOn` 추가 |
| `src/main/java/project/study/user/repository/UserRepository.java` | 기간별 가입 수 카운트 추가 |
| `src/main/java/project/study/user/service/UserService.java` | `countTotal`, `countRegisteredOn` 추가 |
| `src/main/resources/application-prod.yaml` | `metrics.slack.webhook-url` |

---

### Task 1: 발송 이력 테이블과 날짜 선점

하루 1회 발송을 DB가 보장하게 한다. ECS `desired_count=1`이지만 배포 중에는 태스크가 일시적으로 2개가 되므로, 그 시간대에 10시가 걸리면 알림이 두 번 간다.

**Files:**
- Create: `src/main/resources/db/migration/V8__daily_report_log.sql`
- Create: `src/main/java/project/study/metrics/entity/DailyReportLog.java`
- Create: `src/main/java/project/study/metrics/repository/DailyReportLogRepository.java`
- Test: `src/test/java/project/study/metrics/repository/DailyReportLogRepositoryTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `DailyReportLogRepository.claim(LocalDate reportDate)` → `int` (삽입된 행 수: 신규면 1, 이미 있으면 0)

- [ ] **Step 1: 마이그레이션 작성**

`src/main/resources/db/migration/V8__daily_report_log.sql`:

```sql
-- 일일 지표 리포트 발송 이력.
-- ECS 무중단 배포 중에는 태스크가 일시적으로 2개가 되어 스케줄러도 2번 뜬다.
-- report_date를 PK로 두고 삽입 성공 여부로 "오늘 아직 안 보냄"을 판정한다 —
-- 애플리케이션 락 대신 DB 제약으로 동시성을 처리하는 이 레포의 방식(UserRepository.insertIfAbsent)과 같다.
CREATE TABLE "daily_report_log" (
    "report_date" date PRIMARY KEY,
    "sent_at"     timestamptz NOT NULL
);
```

- [ ] **Step 2: 엔티티 작성**

`ddl-auto=validate`라 컬럼명·타입이 마이그레이션과 정확히 일치해야 한다. `BaseTimeEntity`를 상속하지 않는다 — `created_at`/`updated_at` 컬럼이 없기 때문이다.

`src/main/java/project/study/metrics/entity/DailyReportLog.java`:

```java
package project.study.metrics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일일 지표 리포트 발송 이력. 삽입은 네이티브 쿼리(on conflict do nothing)로만 하므로
 * 이 엔티티는 스키마 검증(ddl-auto=validate)과 조회 타입 용도다.
 */
@Table(name = "daily_report_log")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReportLog {

    @Id
    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}
```

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/java/project/study/metrics/repository/DailyReportLogRepositoryTest.java`:

```java
package project.study.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;

// @Transactional: 테스트가 넣은 행을 롤백해 다른 테스트에 새어나가지 않게 한다.
// 같은 트랜잭션 안에서도 on conflict는 직전 삽입행을 보므로 두 번째 claim은 0을 반환한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DailyReportLogRepositoryTest {

    private static final LocalDate REPORT_DATE = LocalDate.of(2020, 1, 10);

    @Autowired
    private DailyReportLogRepository dailyReportLogRepository;

    @Test
    void 처음_선점하면_1을_반환한다() {
        assertThat(dailyReportLogRepository.claim(REPORT_DATE)).isEqualTo(1);
    }

    @Test
    void 같은_날짜를_다시_선점하면_0을_반환한다() {
        dailyReportLogRepository.claim(REPORT_DATE);

        assertThat(dailyReportLogRepository.claim(REPORT_DATE)).isZero();
    }

    @Test
    void 다른_날짜는_각각_선점된다() {
        assertThat(dailyReportLogRepository.claim(REPORT_DATE)).isEqualTo(1);
        assertThat(dailyReportLogRepository.claim(REPORT_DATE.plusDays(1))).isEqualTo(1);
    }
}
```

- [ ] **Step 4: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.metrics.repository.DailyReportLogRepositoryTest"`
Expected: 컴파일 실패 — `DailyReportLogRepository` 심볼을 찾을 수 없음

- [ ] **Step 5: 리포지토리 구현**

`@Transactional`을 리포지토리 메서드에 직접 붙인다. `DailyReportService`가 자기 자신의 메서드를 호출하면 Spring 프록시를 거치지 않아 트랜잭션이 걸리지 않고(self-invocation), 반대로 `sendDailyReport()` 전체를 트랜잭션으로 감싸면 Slack HTTP 호출 동안 DB 커넥션을 붙잡게 된다.

`src/main/java/project/study/metrics/repository/DailyReportLogRepository.java`:

```java
package project.study.metrics.repository;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.entity.DailyReportLog;

public interface DailyReportLogRepository extends JpaRepository<DailyReportLog, LocalDate> {

    /**
     * 오늘 리포트를 선점한다. 삽입에 성공하면(1) 이 인스턴스가 발송 주체이고,
     * 0이면 다른 인스턴스가 이미 보냈다는 뜻이다.
     *
     * <p>@Modifying 쿼리는 트랜잭션이 필요한데, 호출자(DailyReportService)가 자기 메서드를
     * 호출하는 방식으로는 프록시를 거치지 않아 트랜잭션이 걸리지 않는다. 그렇다고 발송
     * 전체를 트랜잭션으로 감싸면 Slack HTTP 호출 동안 DB 커넥션을 점유하게 되므로,
     * 선점만 독립 트랜잭션으로 처리한다.
     */
    @Modifying
    @Transactional
    @Query(value = """
        insert into daily_report_log (report_date, sent_at)
        values (:reportDate, now())
        on conflict (report_date) do nothing
        """, nativeQuery = true)
    int claim(@Param("reportDate") LocalDate reportDate);
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.metrics.repository.DailyReportLogRepositoryTest"`
Expected: 3개 모두 PASS

- [ ] **Step 7: 전체 검증 후 커밋**

```bash
./gradlew spotlessApply
./gradlew check
git add src/main/resources/db/migration/V8__daily_report_log.sql \
        src/main/java/project/study/metrics/ \
        src/test/java/project/study/metrics/
git commit -m "feat: 일일 리포트 발송 이력 테이블과 날짜 선점 추가"
```

---

### Task 2: 헤비유저 조회

이 설계의 핵심 도메인 로직이다. ADR-0009의 스트릭 인정 기준(`focusSec >= 600`, 세션 단위)을 재사용해 "최근 7일 중 인정일 3일 이상"을 판정한다.

**Files:**
- Create: `src/main/java/project/study/studysession/dto/HeavyUser.java`
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRepository.java` (파일 끝에 쿼리 추가)
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java` (상수 2개 + 메서드 1개 추가)
- Test: `src/test/java/project/study/studysession/HeavyUserQueryIntegrationTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `record HeavyUser(Long userId, Long activeDays)` — `project.study.studysession.dto`
  - `StudySessionService.findHeavyUsers(LocalDate anchorDate)` → `List<HeavyUser>` (인정일수 내림차순, 동수면 userId 오름차순)

- [ ] **Step 1: DTO 작성**

`src/main/java/project/study/studysession/dto/HeavyUser.java`:

```java
package project.study.studysession.dto;

/**
 * 헤비유저 조회 결과 — 최근 7일 중 스트릭 인정일(ADR-0009)이 기준치 이상인 유저.
 *
 * @param userId 유저 ID
 * @param activeDays 조회 구간 안에서 스트릭 인정 기준을 만족한 날짜 수
 */
public record HeavyUser(Long userId, Long activeDays) {}
```

`activeDays`가 `long`이 아니라 `Long`인 이유: JPQL `count(distinct ...)`가 `Long`을 반환하므로 생성자 표현식의 타입이 정확히 맞아야 한다.

- [ ] **Step 2: 실패하는 통합 테스트 작성**

`findHeavyUsers`는 특정 유저가 아니라 **전체 유저를 집계**하므로, 다른 테스트가 남긴 데이터가 섞이면 결과가 오염된다. 두 가지로 방어한다: ① `@Transactional`로 이 테스트의 삽입을 롤백, ② 다른 테스트가 쓰지 않는 과거 날짜(2020년)를 앵커로 사용.

`src/test/java/project/study/studysession/HeavyUserQueryIntegrationTest.java`:

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
import project.study.studysession.dto.HeavyUser;
import project.study.studysession.service.StudySessionService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class HeavyUserQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 다른 테스트가 쓰지 않는 과거 날짜 — 전체 집계 쿼리라 데이터가 섞이면 안 된다
    private static final LocalDate ANCHOR = LocalDate.of(2020, 1, 10);
    private static final int QUALIFYING = 600; // 10분 — 스트릭 인정 기준
    private static final int BELOW = 599;

    @Autowired
    private StudySessionService studySessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int sequence = 0;

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString());
    }

    /** started_at은 (user_id, started_at) 유니크 제약이 있어 호출마다 1초씩 밀어 충돌을 피한다. */
    private void insertSession(long userId, LocalDate statDate, int focusSec) {
        Instant startedAt = statDate.atStartOfDay(KST).toInstant().plusSeconds(sequence++);
        jdbcTemplate.update(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plusSeconds(3600)),
                focusSec,
                focusSec);
    }

    private List<Long> heavyUserIds() {
        return studySessionService.findHeavyUsers(ANCHOR).stream()
                .map(HeavyUser::userId)
                .toList();
    }

    @Test
    void 인정일이_3일이면_헤비유저다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(2), QUALIFYING);

        assertThat(heavyUserIds()).contains(userId);
    }

    @Test
    void 인정일이_2일이면_헤비유저가_아니다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 순공시간이_599초인_세션은_인정되지_않는다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, BELOW);
        insertSession(userId, ANCHOR.minusDays(1), BELOW);
        insertSession(userId, ANCHOR.minusDays(2), BELOW);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 같은_날_10분_미만_세션이_여러_개여도_10분_이상_세션이_하나_있으면_그_날은_인정된다() {
        // ADR-0009의 "하나라도" 규칙 — 하루 합계가 아니라 세션 단위 기준이다
        long userId = insertUser();
        insertSession(userId, ANCHOR, BELOW);
        insertSession(userId, ANCHOR, BELOW);
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(2), QUALIFYING);

        assertThat(heavyUserIds()).contains(userId);
    }

    @Test
    void 같은_날_인정_세션이_여러_개여도_하루로_센다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR, QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 구간_시작일인_어제_빼기_6일은_포함된다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(6), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(heavyUserIds()).contains(userId);
    }

    @Test
    void 구간_밖인_어제_빼기_7일은_제외된다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(7), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 앵커보다_뒤인_오늘_세션은_집계에_들어가지_않는다() {
        // 발송 시각(오전 10시) 기준 오늘은 부분 집계라 제외한다는 결정을 고정한다
        long userId = insertUser();
        insertSession(userId, ANCHOR.plusDays(1), QUALIFYING);
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 자정_분할로_조각난_세션은_각_조각이_10분_미만이면_인정되지_않는다() {
        // ADR-0005의 자정 분할은 focusSec을 비율 배분하므로 12분 세션이 5분+7분이 된다.
        // 병합하지 않는 것은 앱 스트릭(ADR-0009)과 같은 판정을 유지하려는 의도적 선택이다 —
        // 누군가 병합 로직을 넣으면 이 테스트가 실패해 불일치를 알린다.
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(1), 300);
        insertSession(userId, ANCHOR, 420);
        insertSession(userId, ANCHOR.minusDays(3), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 인정일수가_많은_유저가_먼저_온다() {
        long lessActive = insertUser();
        insertSession(lessActive, ANCHOR, QUALIFYING);
        insertSession(lessActive, ANCHOR.minusDays(1), QUALIFYING);
        insertSession(lessActive, ANCHOR.minusDays(2), QUALIFYING);

        long moreActive = insertUser();
        for (int i = 0; i < 5; i++) {
            insertSession(moreActive, ANCHOR.minusDays(i), QUALIFYING);
        }

        List<HeavyUser> heavyUsers = studySessionService.findHeavyUsers(ANCHOR).stream()
                .filter(u -> u.userId() == moreActive || u.userId() == lessActive)
                .toList();

        assertThat(heavyUsers).extracting(HeavyUser::userId).containsExactly(moreActive, lessActive);
        assertThat(heavyUsers.get(0).activeDays()).isEqualTo(5L);
        assertThat(heavyUsers.get(1).activeDays()).isEqualTo(3L);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.studysession.HeavyUserQueryIntegrationTest"`
Expected: 컴파일 실패 — `StudySessionService.findHeavyUsers` 메서드 없음

- [ ] **Step 4: 리포지토리 쿼리 추가**

`StudySessionRepository.java`의 마지막 메서드(`findDistinctStatDatesBetween`) 뒤, 인터페이스 닫는 중괄호 앞에 추가한다. import에 `project.study.studysession.dto.HeavyUser`를 더한다.

```java
    // 헤비유저 조회 — 구간 안에서 스트릭 인정 기준(focusSec >= minFocusSec)을 만족한 날이
    // minActiveDays 이상인 유저. 스트릭과 같은 세션 단위 판정이라 자정 분할 조각을 병합하지 않는다
    @Query("""
            select new project.study.studysession.dto.HeavyUser(s.userId, count(distinct s.statDate))
            from StudySession s
            where s.statDate between :from and :to and s.focusSec >= :minFocusSec
            group by s.userId
            having count(distinct s.statDate) >= :minActiveDays
            order by count(distinct s.statDate) desc, s.userId""")
    List<HeavyUser> findHeavyUsers(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("minFocusSec") int minFocusSec,
            @Param("minActiveDays") long minActiveDays);
```

- [ ] **Step 5: 서비스 메서드 추가**

`StudySessionService.java`의 상수 블록(`MIN_STREAK_FOCUS_SEC` 다음 줄)에 추가:

```java
    private static final int HEAVY_USER_WINDOW_DAYS = 7; // 최근 7일(기준일 포함)
    private static final long HEAVY_USER_MIN_ACTIVE_DAYS = 3; // 헤비유저 최소 인정일수
```

`streak` 관련 메서드 근처에 추가. import에 `project.study.studysession.dto.HeavyUser`를 더한다:

```java
    /**
     * 헤비유저 목록 — 기준일 포함 최근 {@value HEAVY_USER_WINDOW_DAYS}일 중 스트릭 인정일이
     * {@value HEAVY_USER_MIN_ACTIVE_DAYS}일 이상인 유저를 인정일수 내림차순으로 반환한다.
     *
     * <p>인정 기준은 스트릭과 같은 {@code MIN_STREAK_FOCUS_SEC}(ADR-0009)이다 — 지표와 앱 화면이
     * 같은 잣대를 쓰도록 상수를 이 클래스 밖으로 내보내지 않고 메서드로만 노출한다.
     *
     * @param anchorDate 집계 기준일(KST). 리포트는 완결된 하루를 쓰기 위해 "어제"를 넣는다
     */
    @Transactional(readOnly = true)
    public List<HeavyUser> findHeavyUsers(LocalDate anchorDate) {
        LocalDate from = anchorDate.minusDays(HEAVY_USER_WINDOW_DAYS - 1L);
        return studySessionRepository.findHeavyUsers(
                from, anchorDate, MIN_STREAK_FOCUS_SEC, HEAVY_USER_MIN_ACTIVE_DAYS);
    }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.studysession.HeavyUserQueryIntegrationTest"`
Expected: 10개 모두 PASS

- [ ] **Step 7: 전체 검증 후 커밋**

```bash
./gradlew spotlessApply
./gradlew check
git add src/main/java/project/study/studysession/ src/test/java/project/study/studysession/HeavyUserQueryIntegrationTest.java
git commit -m "feat: 헤비유저 조회 추가 (ADR-0009 스트릭 기준 재사용)"
```

---

### Task 3: 나머지 지표 3개

가입 수 2개(`user` 도메인)와 어제 10분 이상 세션 수 1개(`studysession` 도메인).

**Files:**
- Modify: `src/main/java/project/study/user/repository/UserRepository.java`
- Modify: `src/main/java/project/study/user/service/UserService.java`
- Modify: `src/main/java/project/study/studysession/repository/StudySessionRepository.java`
- Modify: `src/main/java/project/study/studysession/service/StudySessionService.java`
- Test: `src/test/java/project/study/metrics/MetricsQueryIntegrationTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `UserService.countTotal()` → `long`
  - `UserService.countRegisteredOn(LocalDate date)` → `long` (해당 KST 날짜에 가입한 수)
  - `StudySessionService.countQualifyingSessionsOn(LocalDate date)` → `long` (해당 날짜의 `focusSec >= 600` 세션 수)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`src/test/java/project/study/metrics/MetricsQueryIntegrationTest.java`:

```java
package project.study.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.studysession.service.StudySessionService;
import project.study.user.service.UserService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class MetricsQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TARGET = LocalDate.of(2020, 1, 10);

    @Autowired
    private UserService userService;

    @Autowired
    private StudySessionService studySessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int sequence = 0;

    /** created_at은 @CreatedDate가 now()로 채우므로, 과거 가입을 만들려면 직접 넣어야 한다. */
    private long insertUserCreatedAt(Instant createdAt) {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, ?, ?) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    private void insertSession(long userId, LocalDate statDate, int focusSec) {
        Instant startedAt = statDate.atStartOfDay(KST).toInstant().plusSeconds(sequence++);
        jdbcTemplate.update(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plusSeconds(3600)),
                focusSec,
                focusSec);
    }

    @Test
    void 총_가입_수는_유저를_넣은_만큼_늘어난다() {
        long before = userService.countTotal();

        insertUserCreatedAt(Instant.parse("2020-01-10T05:00:00Z"));
        insertUserCreatedAt(Instant.parse("2020-01-10T06:00:00Z"));

        assertThat(userService.countTotal()).isEqualTo(before + 2);
    }

    @Test
    void 해당_날짜_KST_범위에_가입한_유저만_센다() {
        long before = userService.countRegisteredOn(TARGET);

        // KST 2020-01-10 00:00:00 = UTC 2020-01-09 15:00:00 (경계 포함)
        insertUserCreatedAt(Instant.parse("2020-01-09T15:00:00Z"));
        // KST 2020-01-10 23:59:59
        insertUserCreatedAt(Instant.parse("2020-01-10T14:59:59Z"));
        // KST 2020-01-09 23:59:59 — 전날이라 제외
        insertUserCreatedAt(Instant.parse("2020-01-09T14:59:59Z"));
        // KST 2020-01-11 00:00:00 — 다음날이라 제외
        insertUserCreatedAt(Instant.parse("2020-01-10T15:00:00Z"));

        assertThat(userService.countRegisteredOn(TARGET)).isEqualTo(before + 2);
    }

    @Test
    void 해당_날짜의_10분_이상_세션만_센다() {
        long before = studySessionService.countQualifyingSessionsOn(TARGET);
        long userId = insertUserCreatedAt(Instant.parse("2020-01-10T05:00:00Z"));

        insertSession(userId, TARGET, 600);
        insertSession(userId, TARGET, 3600);
        insertSession(userId, TARGET, 599); // 미만이라 제외
        insertSession(userId, TARGET.minusDays(1), 600); // 다른 날이라 제외

        assertThat(studySessionService.countQualifyingSessionsOn(TARGET)).isEqualTo(before + 2);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.metrics.MetricsQueryIntegrationTest"`
Expected: 컴파일 실패 — `countTotal`, `countRegisteredOn`, `countQualifyingSessionsOn` 없음

- [ ] **Step 3: UserRepository에 카운트 추가**

`UserRepository.java`의 `insertIfAbsent` 뒤에 추가. import에 `java.time.Instant` 필요.

```java
    // 기간별 가입 수 — Between은 양끝 포함이라 하루 경계에서 다음날 00:00:00.000을 삼킨다.
    // 반개구간 [from, to)로 세어 날짜 간 중복 집계를 막는다
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
```

- [ ] **Step 4: UserService에 메서드 추가**

`UserService.java`에 상수와 메서드를 추가한다. import에 `java.time.Instant`, `java.time.LocalDate`, `java.time.ZoneId`, `org.springframework.transaction.annotation.Transactional`(이미 있음) 필요.

```java
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Transactional(readOnly = true)
    public long countTotal() {
        return userRepository.count();
    }

    /**
     * 해당 날짜(KST)에 가입한 유저 수.
     *
     * <p>가입 수는 현재 실제보다 크다 — 구버전 앱 빌드가 첫 실행에서 서로 다른 UUID로 등록을
     * 두 번 호출해 유저가 2건씩 생긴다. 어느 쪽이 중복인지 서버가 판별할 수 없어 보정하지 않는다
     * (설계 문서의 "알려진 한계" 참고). 수정된 빌드가 퍼지면 자연히 정확해진다.
     */
    @Transactional(readOnly = true)
    public long countRegisteredOn(LocalDate date) {
        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();
        return userRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
    }
```

- [ ] **Step 5: StudySession 쪽 카운트 추가**

`StudySessionRepository.java`에 추가:

```java
    // 특정 날짜의 인정 기준(focusSec >= minFocusSec) 충족 세션 수 — 유저 무관 전체 집계
    long countByStatDateAndFocusSecGreaterThanEqual(LocalDate statDate, int minFocusSec);
```

`StudySessionService.java`의 `findHeavyUsers` 뒤에 추가:

```java
    /**
     * 해당 날짜에 스트릭 인정 기준을 충족한 세션 수(전체 유저 합계).
     * 헤비유저·스트릭과 같은 {@code MIN_STREAK_FOCUS_SEC} 잣대를 쓴다.
     */
    @Transactional(readOnly = true)
    public long countQualifyingSessionsOn(LocalDate date) {
        return studySessionRepository.countByStatDateAndFocusSecGreaterThanEqual(date, MIN_STREAK_FOCUS_SEC);
    }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.metrics.MetricsQueryIntegrationTest"`
Expected: 3개 모두 PASS

- [ ] **Step 7: 전체 검증 후 커밋**

```bash
./gradlew spotlessApply
./gradlew check
git add src/main/java/project/study/user/ src/main/java/project/study/studysession/ \
        src/test/java/project/study/metrics/MetricsQueryIntegrationTest.java
git commit -m "feat: 일일 리포트용 가입 수·세션 수 집계 추가"
```

---

### Task 4: Slack 발송기

**Files:**
- Create: `src/main/java/project/study/metrics/slack/SlackNotifier.java`
- Create: `src/main/java/project/study/metrics/slack/SlackWebhookNotifier.java`
- Modify: `src/main/resources/application-prod.yaml`
- Test: `src/test/java/project/study/metrics/slack/SlackWebhookNotifierTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `SlackNotifier.isEnabled()` → `boolean`
  - `SlackNotifier.send(String message)` → `void`

- [ ] **Step 1: 실패하는 테스트 작성**

실제 HTTP 호출은 검증 범위 밖이다(외부 서비스 의존 테스트는 불안정하다). URL 유무에 따른 활성화 판정과, 비활성 상태에서 예외 없이 무시되는지만 고정한다.

`src/test/java/project/study/metrics/slack/SlackWebhookNotifierTest.java`:

```java
package project.study.metrics.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SlackWebhookNotifierTest {

    private SlackWebhookNotifier notifier(String webhookUrl) {
        return new SlackWebhookNotifier(RestClient.builder(), webhookUrl);
    }

    @Test
    void webhook_URL이_있으면_활성화된다() {
        assertThat(notifier("https://hooks.slack.test/services/T/B/X").isEnabled())
                .isTrue();
    }

    @Test
    void webhook_URL이_비어있으면_비활성이다() {
        assertThat(notifier("").isEnabled()).isFalse();
    }

    @Test
    void webhook_URL이_공백뿐이어도_비활성이다() {
        assertThat(notifier("   ").isEnabled()).isFalse();
    }

    @Test
    void 비활성_상태에서_발송해도_예외를_던지지_않는다() {
        // 설정 누락이 예외로 번지면 스케줄러가 매일 실패 알림을 만든다 — 조용히 넘긴다
        assertThatCode(() -> notifier("").send("무시되는 메시지")).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.metrics.slack.SlackWebhookNotifierTest"`
Expected: 컴파일 실패 — `SlackWebhookNotifier` 없음

- [ ] **Step 3: 인터페이스 작성**

`src/main/java/project/study/metrics/slack/SlackNotifier.java`:

```java
package project.study.metrics.slack;

/**
 * Slack 메시지 발송. 인터페이스로 두어 단위 테스트에서 실제 HTTP 없이 대체할 수 있게 한다.
 */
public interface SlackNotifier {

    /** 발송 수단이 설정돼 있는지. 비활성이면 호출자는 발송 이력을 남기지 않고 건너뛴다. */
    boolean isEnabled();

    void send(String message);
}
```

- [ ] **Step 4: 구현 작성**

`src/main/java/project/study/metrics/slack/SlackWebhookNotifier.java`:

```java
package project.study.metrics.slack;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Slack Incoming Webhook 발송기.
 *
 * <p>기존 {@code infra/monitoring.tf}의 AWS Chatbot 연동은 SNS 토픽을 구독하는 CloudWatch
 * 알람 전용이라, 정해진 시각에 임의 메시지를 보내는 용도로는 재사용할 수 없다.
 */
@Slf4j
@Component
public class SlackWebhookNotifier implements SlackNotifier {

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackWebhookNotifier(
            RestClient.Builder restClientBuilder, @Value("${metrics.slack.webhook-url:}") String webhookUrl) {
        this.restClient = restClientBuilder.build();
        this.webhookUrl = webhookUrl;
        if (!isEnabled()) {
            log.warn("metrics.slack.webhook-url이 비어 있어 일일 지표 리포트를 발송하지 않는다");
        }
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.hasText(webhookUrl);
    }

    @Override
    public void send(String message) {
        if (!isEnabled()) {
            return;
        }
        restClient
                .post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", message))
                .retrieve()
                .toBodilessEntity();
    }
}
```

- [ ] **Step 5: prod 설정 추가**

`application-prod.yaml`의 `sentry:` 블록 앞에 추가한다:

```yaml
# 일일 지표 리포트를 보낼 Slack Incoming Webhook.
# URL이 없으면 발송기가 비활성으로 동작한다 — Sentry DSN과 같은 이유로 기본값을 둔다:
# 모니터링 설정 하나가 없다고 API 전체가 기동 실패하는 쪽이 더 나쁘다.
# ECS 태스크 정의가 SSM Parameter Store 값을 SLACK_WEBHOOK_URL로 주입한다.
metrics:
  slack:
    webhook-url: ${SLACK_WEBHOOK_URL:}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.metrics.slack.SlackWebhookNotifierTest"`
Expected: 4개 모두 PASS

- [ ] **Step 7: 전체 검증 후 커밋**

```bash
./gradlew spotlessApply
./gradlew check
git add src/main/java/project/study/metrics/slack/ src/main/resources/application-prod.yaml \
        src/test/java/project/study/metrics/slack/
git commit -m "feat: Slack Incoming Webhook 발송기 추가"
```

---

### Task 5: 리포트 조립과 발송 조율

**Files:**
- Create: `src/main/java/project/study/metrics/dto/DailyReport.java`
- Create: `src/main/java/project/study/metrics/service/DailyReportService.java`
- Test: `src/test/java/project/study/metrics/service/DailyReportServiceTest.java`
- Test: `src/test/java/project/study/metrics/dto/DailyReportTest.java`

**Interfaces:**
- Consumes: `DailyReportLogRepository.claim`, `UserService.countTotal/countRegisteredOn`, `StudySessionService.findHeavyUsers/countQualifyingSessionsOn`, `SlackNotifier.isEnabled/send`, `HeavyUser`
- Produces: `DailyReportService.sendDailyReport()` → `void`

- [ ] **Step 1: 메시지 포맷 테스트 작성**

`src/test/java/project/study/metrics/dto/DailyReportTest.java`:

```java
package project.study.metrics.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.studysession.dto.HeavyUser;

class DailyReportTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 8);

    @Test
    void 헤비유저가_있으면_수와_ID_인정일수를_함께_적는다() {
        DailyReport report =
                new DailyReport(DATE, 53, 4, List.of(new HeavyUser(14L, 7L), new HeavyUser(13L, 4L)), 6);

        assertThat(report.toSlackMessage())
                .contains("2026-08-08")
                .contains("총 가입: 53명")
                .contains("어제 신규: 4명")
                .contains("헤비유저: 2명")
                .contains("#14(7일)")
                .contains("#13(4일)")
                .contains("10분 이상 세션: 6건");
    }

    @Test
    void 헤비유저가_없으면_목록_대신_없음을_적는다() {
        DailyReport report = new DailyReport(DATE, 53, 0, List.of(), 0);

        assertThat(report.toSlackMessage()).contains("헤비유저: 0명").contains("없음");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.metrics.dto.DailyReportTest"`
Expected: 컴파일 실패 — `DailyReport` 없음

- [ ] **Step 3: DTO 작성**

`src/main/java/project/study/metrics/dto/DailyReport.java`:

```java
package project.study.metrics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import project.study.studysession.dto.HeavyUser;

/**
 * 일일 지표 리포트.
 *
 * @param reportDate 집계 기준일(KST 어제) — 발송 시각이 오전 10시라 오늘은 부분 집계다
 * @param totalUsers 총 가입 수 (중복 등록 문제로 실제보다 큼 — 설계 문서의 "알려진 한계" 참고)
 * @param newUsers 기준일에 가입한 수
 * @param heavyUsers 최근 7일 헤비유저 (인정일수 내림차순)
 * @param qualifyingSessions 기준일의 10분 이상 세션 수
 */
public record DailyReport(
        LocalDate reportDate, long totalUsers, long newUsers, List<HeavyUser> heavyUsers, long qualifyingSessions) {

    public String toSlackMessage() {
        return """
                📊 %s 지표
                • 총 가입: %d명 (어제 신규: %d명)
                • 헤비유저: %d명 — %s
                • 10분 이상 세션: %d건"""
                .formatted(reportDate, totalUsers, newUsers, heavyUsers.size(), heavyUserList(), qualifyingSessions);
    }

    private String heavyUserList() {
        if (heavyUsers.isEmpty()) {
            return "없음";
        }
        return heavyUsers.stream()
                .map(user -> "#%d(%d일)".formatted(user.userId(), user.activeDays()))
                .collect(Collectors.joining(", "));
    }
}
```

- [ ] **Step 4: 포맷 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.metrics.dto.DailyReportTest"`
Expected: 2개 PASS

- [ ] **Step 5: 서비스 테스트 작성**

`src/test/java/project/study/metrics/service/DailyReportServiceTest.java`:

```java
package project.study.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.repository.DailyReportLogRepository;
import project.study.metrics.slack.SlackNotifier;
import project.study.studysession.dto.HeavyUser;
import project.study.studysession.service.StudySessionService;
import project.study.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    // 고정 현재 시각: 2026-08-09T01:00:00Z = KST 10:00 → KST 오늘 = 08-09, 기준일(어제) = 08-08
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-09T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 8);

    @Mock
    private DailyReportLogRepository dailyReportLogRepository;

    @Mock
    private UserService userService;

    @Mock
    private StudySessionService studySessionService;

    @Mock
    private SlackNotifier slackNotifier;

    private DailyReportService service;

    @BeforeEach
    void setUp() {
        service = new DailyReportService(
                dailyReportLogRepository, userService, studySessionService, slackNotifier, CLOCK);
    }

    private void givenEnabledAndClaimed() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(dailyReportLogRepository.claim(TODAY)).thenReturn(1);
    }

    @Test
    void 선점에_성공하면_기준일은_어제이고_지표_네_개를_모아_발송한다() {
        givenEnabledAndClaimed();
        when(userService.countTotal()).thenReturn(53L);
        when(userService.countRegisteredOn(ANCHOR)).thenReturn(4L);
        when(studySessionService.findHeavyUsers(ANCHOR)).thenReturn(List.of(new HeavyUser(14L, 7L)));
        when(studySessionService.countQualifyingSessionsOn(ANCHOR)).thenReturn(6L);

        service.sendDailyReport();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(slackNotifier).send(message.capture());
        assertThat(message.getValue())
                .contains("2026-08-08")
                .contains("총 가입: 53명")
                .contains("#14(7일)")
                .contains("10분 이상 세션: 6건");
    }

    @Test
    void 이미_발송된_날이면_지표를_조회하지도_발송하지도_않는다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(dailyReportLogRepository.claim(TODAY)).thenReturn(0);

        service.sendDailyReport();

        verifyNoInteractions(userService, studySessionService);
        verify(slackNotifier, never()).send(any());
    }

    @Test
    void 발송기가_비활성이면_날짜를_선점하지_않는다() {
        // 선점해두면 나중에 URL을 설정하고 재배포해도 그날 리포트가 영영 나가지 않는다
        when(slackNotifier.isEnabled()).thenReturn(false);

        service.sendDailyReport();

        verifyNoInteractions(dailyReportLogRepository, userService, studySessionService);
        verify(slackNotifier, never()).send(any());
    }
}
```

- [ ] **Step 6: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.metrics.service.DailyReportServiceTest"`
Expected: 컴파일 실패 — `DailyReportService` 없음

- [ ] **Step 7: 서비스 구현**

`src/main/java/project/study/metrics/service/DailyReportService.java`:

```java
package project.study.metrics.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.metrics.dto.DailyReport;
import project.study.metrics.repository.DailyReportLogRepository;
import project.study.metrics.slack.SlackNotifier;
import project.study.studysession.service.StudySessionService;
import project.study.user.service.UserService;

/**
 * 일일 지표 리포트를 조립해 Slack으로 발송한다.
 *
 * <p>지표는 각자 소유 도메인의 서비스에서 가져온다 — 이 클래스가 users·study_session 테이블을
 * 직접 조회하지 않는다. 특히 헤비유저 판정 기준(ADR-0009)은 StudySessionService 안에만 두어
 * 앱 스트릭과 같은 잣대를 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyReportLogRepository dailyReportLogRepository;
    private final UserService userService;
    private final StudySessionService studySessionService;
    private final SlackNotifier slackNotifier;
    private final Clock clock;

    public void sendDailyReport() {
        if (!slackNotifier.isEnabled()) {
            log.warn("Slack 발송기가 비활성이라 일일 리포트를 건너뛴다 — 날짜를 선점하지 않으므로 설정 후 재시도할 수 있다");
            return;
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        if (dailyReportLogRepository.claim(today) == 0) {
            log.info("일일 리포트가 이미 발송됐다 (reportDate={}) — 배포 중 태스크 중복으로 보고 건너뛴다", today);
            return;
        }

        // 발송 시각이 오전 10시라 오늘은 부분 집계다. 완결된 하루인 어제를 기준일로 쓴다
        LocalDate anchorDate = today.minusDays(1);
        DailyReport report = new DailyReport(
                anchorDate,
                userService.countTotal(),
                userService.countRegisteredOn(anchorDate),
                studySessionService.findHeavyUsers(anchorDate),
                studySessionService.countQualifyingSessionsOn(anchorDate));

        slackNotifier.send(report.toSlackMessage());
        log.info("일일 지표 리포트를 발송했다 (reportDate={})", anchorDate);
    }
}
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.metrics.*"`
Expected: 전부 PASS

- [ ] **Step 9: 전체 검증 후 커밋**

```bash
./gradlew spotlessApply
./gradlew check
git add src/main/java/project/study/metrics/ src/test/java/project/study/metrics/
git commit -m "feat: 일일 지표 리포트 조립·발송 서비스 추가"
```

---

### Task 6: 스케줄러 배선

**Files:**
- Create: `src/main/java/project/study/config/SchedulingConfig.java`
- Create: `src/main/java/project/study/metrics/scheduler/DailyReportScheduler.java`
- Test: `src/test/java/project/study/metrics/scheduler/DailyReportSchedulerTest.java`

**Interfaces:**
- Consumes: `DailyReportService.sendDailyReport()`
- Produces: 없음 (최종 배선)

- [ ] **Step 1: 실패하는 테스트 작성**

스케줄러의 유일한 책임은 "예외를 밖으로 흘리지 않고 Sentry로 올린다"이다. cron 발화 자체는 Spring의 책임이라 테스트하지 않는다.

`src/test/java/project/study/metrics/scheduler/DailyReportSchedulerTest.java`:

```java
package project.study.metrics.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.service.DailyReportService;

@ExtendWith(MockitoExtension.class)
class DailyReportSchedulerTest {

    @Mock
    private DailyReportService dailyReportService;

    @InjectMocks
    private DailyReportScheduler scheduler;

    @Test
    void 리포트_서비스에_위임한다() {
        scheduler.sendDailyReport();

        verify(dailyReportService).sendDailyReport();
    }

    @Test
    void 발송이_실패해도_예외를_밖으로_던지지_않는다() {
        // @Scheduled 메서드에서 예외가 나가면 Spring이 로그만 남기고 삼켜 Sentry에 남지 않는다
        doThrow(new RuntimeException("Slack 500")).when(dailyReportService).sendDailyReport();

        assertThatCode(() -> scheduler.sendDailyReport()).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew test --tests "project.study.metrics.scheduler.DailyReportSchedulerTest"`
Expected: 컴파일 실패 — `DailyReportScheduler` 없음

- [ ] **Step 3: 스케줄링 활성화 설정 작성**

`src/main/java/project/study/config/SchedulingConfig.java`:

```java
package project.study.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 이 프로젝트의 첫 스케줄러(일일 지표 리포트)를 위해 스케줄링을 켠다.
 *
 * <p>테스트 프로파일에는 Slack webhook URL이 없어 발송기가 비활성이므로,
 * 테스트 실행 중 cron이 발화해도 아무 일도 일어나지 않는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
```

- [ ] **Step 4: 스케줄러 작성**

`src/main/java/project/study/metrics/scheduler/DailyReportScheduler.java`:

```java
package project.study.metrics.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.metrics.service.DailyReportService;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportScheduler {

    private final DailyReportService dailyReportService;

    /**
     * 매일 오전 10시(KST) 일일 지표 리포트를 발송한다.
     *
     * <p>zone을 명시하는 이유: ECS 태스크는 UTC로 뜨므로 zone 없이는 KST 오후 7시에 발화한다.
     *
     * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
     * 로그만 남기고 삼켜서, 자동 리졸버(SentryExceptionResolver)가 잡는 HTTP 요청 경로와 달리
     * Sentry에 아무것도 남지 않는다.
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Seoul")
    public void sendDailyReport() {
        try {
            dailyReportService.sendDailyReport();
        } catch (Exception e) {
            log.error("일일 지표 리포트 발송 실패", e);
            Sentry.captureException(e);
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "project.study.metrics.scheduler.DailyReportSchedulerTest"`
Expected: 2개 PASS

- [ ] **Step 6: 전체 검증**

Run: `./gradlew check`
Expected: 전체 PASS (ArchUnit 규칙 포함 — `metrics` 패키지는 `project.study..` 아래라 `servicesShouldBeInDomainPackages`를 만족하고, 컨트롤러가 없어 `controllerShouldNotAccessRepository`와 무관하다)

- [ ] **Step 7: 커밋**

```bash
./gradlew spotlessApply
./gradlew check
git add src/main/java/project/study/config/SchedulingConfig.java \
        src/main/java/project/study/metrics/scheduler/ \
        src/test/java/project/study/metrics/scheduler/
git commit -m "feat: 매일 오전 10시 일일 지표 리포트 스케줄러 추가"
```

---

## 배포 전 수동 작업

코드 밖에서 해야 하는 일이라 태스크에 넣지 않았다. 배포 담당자가 순서대로 처리한다.

1. **Slack Incoming Webhook 발급** — 대상 워크스페이스에서 앱을 만들고 Incoming Webhook을 채널에 연결해 URL(`https://hooks.slack.com/services/...`)을 받는다.
2. **SSM Parameter Store에 저장** — 기존 DB 자격증명과 같은 경로 체계를 따라 `/focus-makers/prod/slack/webhook-url`에 `SecureString`으로 저장한다.
3. **ECS 태스크 정의에 주입 추가** — `task-definition.json`의 `secrets`에 `SLACK_WEBHOOK_URL`을 그 파라미터로 매핑한다. (DB 비밀번호가 주입되는 방식과 동일)
4. **첫 발송 확인** — 배포 다음 날 오전 10시에 메시지가 오는지 본다. 안 오면 CloudWatch 로그에서 `metrics.slack.webhook-url이 비어 있어` WARN을 찾는다 — 이 로그가 있으면 3번이 누락된 것이다.

## 완료 후 후속 작업

- **ADR 추가 여부 판단**: 이 설계는 기존 ADR-0009의 기준을 재사용할 뿐 새 결정을 만들지 않으므로 ADR은 필요 없다고 판단했다. 다만 "지표를 BI 도구가 아닌 백엔드에 두기로 한 선택"은 설계 문서에 근거가 남아 있다.
- **크로스 코드체크**: 커밋 전 `/codex review`로 독립 2차 리뷰 (P1 발견 시 FAIL 게이트) — CLAUDE.md 규칙.
- **퀴즈 게이트**: 구현 완료 후 커밋 전 구현 코드·흐름 퀴즈 5개 — CLAUDE.md 규칙.
