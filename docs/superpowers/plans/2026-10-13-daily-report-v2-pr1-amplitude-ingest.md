# 일일 리포트 v2 — PR ① Amplitude 적재 구현 계획 (BY-904)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매일 09:00(KST) Amplitude Export를 받아 유저×날짜×이벤트 횟수와 유저 속성을 DB에 적재한다. 리포트(PR ②)는 이 테이블만 읽는다.

**Architecture:** 수신 시각 기준 KST 하루 창(`ExportWindow`) 단위로 HTTP 다운로드 → zip/gzip 줄 단위 파싱(`AmplitudeEventParser`) → 한 트랜잭션에서 창 처리 기록 선점 + 횟수 가산 upsert(`AmplitudeWindowWriter`). 스케줄 메서드는 metrics 전용 단일 스레드(`MetricsJobRunner`, 빈 아님)에 제출만 한다.

**Tech Stack:** Spring Boot 4.1, Java 25, Spring `RestClient`(JDK HttpClient), Jackson 3(`tools.jackson`), JdbcClient/JdbcTemplate, Flyway, Testcontainers PostgreSQL 17, JDK `HttpServer` 스텁.

**Spec:** `docs/superpowers/specs/2026-10-13-daily-report-v2-design.md` (§7 적재, §10 실패 처리, §11 설정, §13 배포). 결정 기록 ADR-0030.

## Global Constraints

- 새 의존성 없음. zip/gzip은 JDK(`java.util.zip`), HTTP는 기존 `RestClient.Builder` 빈(`StudyApplication`)을 `clone()`해 쓴다.
- Jackson은 `tools.jackson` 패키지만 쓴다(`com.fasterxml.jackson` 금지). 정적 `JsonMapper.builder().build()`는 `TaskIdentity` 선례.
- 생성자 주입만(`@Autowired` 금지 — ArchUnit), `@Profile` 금지. 기능 스위치는 `@ConditionalOnProperty`.
- 스케줄 잡은 `app.report.enabled=true`일 때만 뜬다(prod yaml만 켬). `app.report.run-once`는 값이 없으면 꺼짐(BY-640).
- `TaskScheduler`·`Executor`·`ScheduledExecutorService` 타입 빈을 새로 만들지 않는다(ADR-0030 결정 7).
- Checkstyle: 파일 400줄, 메서드 60줄, 순환 복잡도 10, 파라미터 7개 이하. Spotless(palantir 2.96.0) — 커밋 전 `./gradlew spotlessApply`.
- 시크릿(API 키·시크릿 키·Authorization 헤더)은 로그·예외 메시지 어디에도 남기지 않는다. 예외는 원인(cause)을 붙이지 않고 상태 코드·예외 클래스 이름만 쓴다(`SlackNotificationException` 선례).
- 마이그레이션 번호는 V28(분기 기준 origin/dev `a9cd288`의 최신이 V27).
- 테스트 이름은 한글 서술형(`작업은_..._한다`), AssertJ.
- **커밋 규칙**: CLAUDE.md 퀴즈 게이트 때문에 태스크마다 커밋하지 않는다. 각 태스크는 테스트 통과까지 확인하고, Task 7에서 `./gradlew check` → 퀴즈 통과 → 목적별로 나눠 커밋한다.

## Review Focus

1. **200인데 zip이 아닌 본문**(프록시 HTML 오류 페이지 등) — 조용히 0건으로 처리 기록되면 그날 행동이 영영 0이 된다. zip 항목이 하나도 없으면 예외로 창을 실패시킨다 → Task 2 테스트 `zip_항목이_하나도_없으면_예외다`.
2. **`event_time` 형식 변형**(소수점 자릿수 0~6) — 한 형식만 읽으면 해당 이벤트가 전부 건너뛰어진다 → Task 2 테스트 `event_time은_소수점이_없어도_읽는다`.
3. **120자 넘는 이벤트 이름** — DB 컬럼 초과로 창 전체가 롤백되면 그날 적재가 매일 실패한다. 120자로 자른다 → Task 2 테스트 `이벤트_키는_120자로_자른다`.
4. **KST 자정 경계** — 창 파라미터가 한 시간만 어긋나도 이벤트가 두 창에 중복되거나 빠진다 → Task 1 테스트 `KST_하루_창은_전날_UTC_15시부터_그날_UTC_14시까지다`.
5. **배포 중 태스크 둘이 같은 창을 적재** — 횟수가 두 배가 된다. 처리 기록 선점으로 한 번만 쓴다 → Task 4 테스트 `같은_창을_두_번_쓰면_두_번째는_아무것도_쓰지_않는다`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `src/main/resources/db/migration/V28__amplitude_ingest.sql` | 테이블 3개 |
| `metrics/amplitude/ExportWindow.java` | 창 하나의 날짜·Export 파라미터·준비 시각 |
| `metrics/amplitude/DailyEventKey.java` | (userId, 발생일, 이벤트 키) |
| `metrics/amplitude/UserAttributeObservation.java` | 유저 속성 관측과 병합 규칙 |
| `metrics/amplitude/WindowAggregate.java` | 창 하나의 집계 누적기 |
| `metrics/amplitude/AmplitudeEventParser.java` | zip→gzip→JSON 줄 파싱 |
| `metrics/amplitude/AmplitudeExportSource.java` | 창 하나를 받아 오는 포트(테스트에서 가짜로 대체) |
| `metrics/amplitude/HttpAmplitudeExportSource.java` | Export API HTTP 구현 |
| `metrics/amplitude/LimitedInputStream.java` | 다운로드 크기 상한 |
| `metrics/amplitude/AmplitudeExportException.java`, `ExportTooLargeException.java` | 예외 |
| `metrics/amplitude/repository/AmplitudeIngestRepository.java` | 창 선점·횟수 가산·속성 upsert·처리일 조회 |
| `metrics/amplitude/AmplitudeWindowWriter.java` | 창 하나 = 트랜잭션 하나 |
| `metrics/amplitude/AmplitudeIngestService.java` | 처리 대상 창 계산·순서대로 적재·실패 격리 |
| `metrics/amplitude/AmplitudeIngestJob.java` | 09:00 스케줄 → 실행기 제출 |
| `metrics/job/MetricsJobRunner.java` | 단일 스레드 실행기(빈 아님), 예외 → Sentry |
| `metrics/job/MetricsRunOnce.java` | `app.report.run-once=true`면 기동 시 한 번 |

(`metrics/` = `src/main/java/project/study/metrics/`, 테스트는 같은 경로의 `src/test/java/...`)

---

### Task 0: 스파이크 — 실제 Export 하루치 확인 (사용자 키 필요, 구현과 병행)

**Files:** 없음(코드는 버린다). 결과만 스펙 §7.1에 적는다.

- [ ] **Step 1: 사용자에게 prod Amplitude API 키·시크릿 키를 셸 환경변수로 받아 둔다** (채팅에 붙여넣지 않게 `! export AMPLITUDE_API_KEY=...` 안내)

- [ ] **Step 2: 하루치(10/12 KST 창)를 받아 크기·시간을 잰다**

```bash
mkdir -p "$SCRATCH/amp" && cd "$SCRATCH/amp"
time curl -sS -u "$AMPLITUDE_API_KEY:$AMPLITUDE_SECRET_KEY" \
  'https://amplitude.com/api/2/export?start=20261011T15&end=20261012T14' -o day.zip -w '%{http_code} %{size_download}\n'
unzip -l day.zip | tail -3
```
Expected: `200 <바이트>`, 시간별 `.json.gz` 항목들.

- [ ] **Step 3: 이벤트 수·user_id 비율·UTM/광고 클릭 값 채움을 본다**

```bash
unzip -p day.zip | gunzip -c > day.jsonl
wc -l day.jsonl
python3 -I -c "
import json,collections,sys
c=collections.Counter(); utm=collections.Counter(); ad=0; uid=0; n=0
for l in open('day.jsonl'):
    e=json.loads(l); n+=1; uid+= e.get('user_id') is not None
    up=e.get('user_properties') or {}
    utm[up.get('initial_utm_source')]+=1
    ad+= any(up.get(k) not in (None,'','EMPTY') for k in ('initial_fbclid','initial_gclid','initial_ttclid'))
    c[e.get('event_time','')[19:20]]+=1
print(n, uid, ad); print(utm.most_common(5)); print(c)"
```

- [ ] **Step 4: 결과를 스펙 §7.1의 "값 채움 여부 미확인" 자리에 적고 `day.*`를 지운다.**

---

### Task 1: V28 테이블과 창 계산

**Files:**
- Create: `src/main/resources/db/migration/V28__amplitude_ingest.sql`
- Create: `src/main/java/project/study/metrics/amplitude/ExportWindow.java`
- Test: `src/test/java/project/study/metrics/amplitude/ExportWindowTest.java`

**Interfaces:**
- Produces: `record ExportWindow(LocalDate date)` — `static final ZoneId KST`, `Instant startInclusive()`, `Instant endExclusive()`, `String startParam()`, `String endParam()`, `boolean isReady(Instant now)`.

- [ ] **Step 1: 실패하는 테스트**

```java
package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ExportWindowTest {

    private final ExportWindow window = new ExportWindow(LocalDate.of(2026, 10, 12));

    @Test
    void KST_하루_창은_전날_UTC_15시부터_그날_UTC_14시까지다() {
        assertThat(window.startParam()).isEqualTo("20261011T15");
        assertThat(window.endParam()).isEqualTo("20261012T14");
        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2026-10-11T15:00:00Z"));
        assertThat(window.endExclusive()).isEqualTo(Instant.parse("2026-10-12T15:00:00Z"));
    }

    @Test
    void 창은_끝나고_3시간이_지나야_준비된다() {
        assertThat(window.isReady(Instant.parse("2026-10-12T17:59:59Z"))).isFalse();
        assertThat(window.isReady(Instant.parse("2026-10-12T18:00:00Z"))).isTrue();
    }

    @Test
    void 월초_연초_경계도_전날_15시로_넘어간다() {
        ExportWindow newYear = new ExportWindow(LocalDate.of(2027, 1, 1));

        assertThat(newYear.startParam()).isEqualTo("20261231T15");
        assertThat(newYear.endParam()).isEqualTo("20270101T14");
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.ExportWindowTest"` / Expected: 컴파일 실패(`ExportWindow` 없음)

- [ ] **Step 3: 구현**

`ExportWindow.java`:
```java
package project.study.metrics.amplitude;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Amplitude Export 적재 창 — KST 날짜 하루에 해당하는 수신 시각(server_upload_time) 24시간.
 *
 * <p>Export의 기간은 이벤트 발생 시각이 아니라 Amplitude 서버 수신 시각 기준이라, 창은 수신 시각으로 자르고 이벤트 날짜는
 * 발생 시각으로 매긴다. 늦게 올라온 이벤트는 나중 창에서 제 날짜로 들어간다 (ADR-0030 결정 5).
 *
 * @param date 창이 덮는 KST 날짜
 */
public record ExportWindow(LocalDate date) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // Export 파라미터는 UTC 시 단위 `YYYYMMDDTHH`이고 양끝을 포함한다
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HH").withZone(ZoneOffset.UTC);
    // Amplitude는 수신 후 최대 2시간 뒤에 Export에 내놓는다 — 1시간 여유를 더 둔다
    private static final Duration EXPORT_DELAY = Duration.ofHours(3);

    public Instant startInclusive() {
        return date.atStartOfDay(KST).toInstant();
    }

    public Instant endExclusive() {
        return date.plusDays(1).atStartOfDay(KST).toInstant();
    }

    public String startParam() {
        return HOUR.format(startInclusive());
    }

    public String endParam() {
        return HOUR.format(endExclusive().minus(Duration.ofHours(1)));
    }

    public boolean isReady(Instant now) {
        return !now.isBefore(endExclusive().plus(EXPORT_DELAY));
    }
}
```

`V28__amplitude_ingest.sql`:
```sql
-- Amplitude Export 일 단위 적재 (BY-904, ADR-0030). 원본 이벤트는 저장하지 않는다.

-- 처리한 수신 시각 창(KST 날짜 하나). PK 선점으로 재실행·태스크 중복에도 한 번만 센다
CREATE TABLE amplitude_export_window (
    window_date  DATE        PRIMARY KEY,
    event_count  INT         NOT NULL,
    skipped      INT         NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 유저 × 발생일(KST) × 이벤트 키 횟수. 키는 event_type 또는 "event_type:값"(속성값 파생 키).
-- user_id에 외래키를 걸지 않는다 — Amplitude에는 탈퇴·타 환경 id가 섞일 수 있어 리포트가 users와 맞춰 거른다
CREATE TABLE amplitude_daily_event (
    user_id    BIGINT       NOT NULL,
    event_date DATE         NOT NULL,
    event_key  VARCHAR(120) NOT NULL,
    count      INT          NOT NULL,
    PRIMARY KEY (user_id, event_date, event_key)
);
CREATE INDEX idx_amplitude_daily_event_date ON amplitude_daily_event (event_date);

-- 유저 속성 최신값. 광고 클릭 유입은 한 번이라도 보이면 true로 남는다
CREATE TABLE amplitude_user_attribute (
    user_id       BIGINT       PRIMARY KEY,
    utm_source    VARCHAR(200),
    ad_click      BOOLEAN      NOT NULL DEFAULT false,
    os_name       VARCHAR(50),
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL
);
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.ExportWindowTest"` / Expected: PASS(3)

---

### Task 2: 집계 모델과 Export 파서

**Files:**
- Create: `metrics/amplitude/DailyEventKey.java`, `UserAttributeObservation.java`, `WindowAggregate.java`, `AmplitudeEventParser.java`, `AmplitudeExportException.java`
- Test: `src/test/java/project/study/metrics/amplitude/AmplitudeEventParserTest.java`, `src/test/java/project/study/metrics/amplitude/ExportZipFixture.java`

**Interfaces:**
- Consumes: `ExportWindow.KST`
- Produces:
  - `record DailyEventKey(long userId, LocalDate eventDate, String eventKey)`
  - `record UserAttributeObservation(String utmSource, boolean adClick, String osName, Instant firstSeenAt, Instant lastSeenAt)` + `UserAttributeObservation merge(UserAttributeObservation other)`
  - `final class WindowAggregate` — `static WindowAggregate empty()`, `Map<DailyEventKey, Integer> counts()`, `Map<Long, UserAttributeObservation> attributes()`, `int eventCount()`, `int skipped()`
  - `final class AmplitudeEventParser` — `static WindowAggregate parse(InputStream zip) throws IOException`
  - `class AmplitudeExportException extends RuntimeException` — `(String message)`

- [ ] **Step 1: 테스트 픽스처**

`ExportZipFixture.java`:
```java
package project.study.metrics.amplitude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Export 응답과 같은 모양(zip 안에 gzip JSON 줄 파일)을 만든다. 항목 순서는 넘긴 Map 순서. */
final class ExportZipFixture {

    private ExportZipFixture() {}

    static byte[] zip(Map<String, List<String>> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getKey().endsWith(".gz") ? gzip(entry.getValue()) : join(entry.getValue()));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static String event(String userId, String eventType, String eventTime, String props, String userProps) {
        String user = userId == null ? "null" : "\"" + userId + "\"";
        return """
                {"user_id":%s,"event_type":"%s","event_time":"%s","os_name":"ios","event_properties":%s,"user_properties":%s}"""
                .formatted(user, eventType, eventTime, props, userProps);
    }

    static String event(String userId, String eventType, String eventTime) {
        return event(userId, eventType, eventTime, "{}", "{}");
    }

    private static byte[] gzip(List<String> lines) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(join(lines));
        }
        return bytes.toByteArray();
    }

    private static byte[] join(List<String> lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: 실패하는 테스트**

`AmplitudeEventParserTest.java`:
```java
package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.metrics.amplitude.ExportZipFixture.event;
import static project.study.metrics.amplitude.ExportZipFixture.zip;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmplitudeEventParserTest {

    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);

    private static WindowAggregate parse(Map<String, List<String>> entries) throws IOException {
        return AmplitudeEventParser.parse(new ByteArrayInputStream(zip(entries)));
    }

    private static WindowAggregate parse(List<String> lines) throws IOException {
        return parse(Map.of("123/123_2026-10-12_3#0.json.gz", lines));
    }

    @Test
    void 유저_발생일_이벤트별로_센다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00.000000"),
                event("7", "app_launched", "2026-10-12 02:00:00.000000"),
                event("8", "planner_opened", "2026-10-12 03:00:00.000000")));

        assertThat(result.counts())
                .containsOnly(
                        Map.entry(new DailyEventKey(7, OCT_12, "app_launched"), 2),
                        Map.entry(new DailyEventKey(8, OCT_12, "planner_opened"), 1));
        assertThat(result.eventCount()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void 발생_시각이_UTC_15시_이후면_KST_다음_날로_센다() throws IOException {
        WindowAggregate result = parse(List.of(event("7", "app_launched", "2026-10-12 15:00:00.000000")));

        assertThat(result.counts()).containsOnlyKeys(new DailyEventKey(7, OCT_12.plusDays(1), "app_launched"));
    }

    @Test
    void user_id가_없거나_숫자가_아니거나_줄이_깨지면_건너뛰고_센다() throws IOException {
        WindowAggregate result = parse(List.of(
                event(null, "invite_link_opened", "2026-10-12 01:00:00.000000"),
                event("abc", "app_launched", "2026-10-12 01:00:00.000000"),
                "{not json",
                event("7", "app_launched", "2026-10-12 01:00:00.000000")));

        assertThat(result.eventCount()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    void 켜기_가로모드_이벤트는_속성값_키를_함께_센다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "session_simple_mode_toggled", "2026-10-12 01:00:00.000000", "{\"on\":true}", "{}"),
                event("7", "session_simple_mode_toggled", "2026-10-12 01:01:00.000000", "{\"on\":false}", "{}"),
                event("7", "social_room_camera_toggled", "2026-10-12 01:02:00.000000", "{\"on\":true}", "{}"),
                event("7", "session_orientation_changed", "2026-10-12 01:03:00.000000",
                        "{\"orientation\":\"landscape\"}", "{}"),
                event("7", "session_orientation_changed", "2026-10-12 01:04:00.000000",
                        "{\"orientation\":\"portrait\"}", "{}")));

        assertThat(result.counts())
                .containsEntry(new DailyEventKey(7, OCT_12, "session_simple_mode_toggled"), 2)
                .containsEntry(new DailyEventKey(7, OCT_12, "session_simple_mode_toggled:on"), 1)
                .containsEntry(new DailyEventKey(7, OCT_12, "social_room_camera_toggled:on"), 1)
                .containsEntry(new DailyEventKey(7, OCT_12, "session_orientation_changed"), 2)
                .containsEntry(new DailyEventKey(7, OCT_12, "session_orientation_changed:landscape"), 1);
    }

    @Test
    void 광고_클릭_ID와_UTM은_EMPTY를_없음으로_본다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00.000000", "{}",
                        "{\"initial_fbclid\":\"IwAR0abc\",\"initial_utm_source\":\"instagram\"}"),
                event("8", "app_launched", "2026-10-12 01:00:00.000000", "{}",
                        "{\"initial_fbclid\":\"EMPTY\",\"initial_utm_source\":\"EMPTY\"}")));

        assertThat(result.attributes().get(7L))
                .returns(true, UserAttributeObservation::adClick)
                .returns("instagram", UserAttributeObservation::utmSource)
                .returns("ios", UserAttributeObservation::osName);
        assertThat(result.attributes().get(8L))
                .returns(false, UserAttributeObservation::adClick)
                .returns(null, UserAttributeObservation::utmSource);
    }

    @Test
    void 같은_유저의_속성은_늦게_본_값이_이기고_처음_마지막_시각을_남긴다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 05:00:00.000000", "{}", "{\"initial_utm_source\":\"late\"}"),
                event("7", "app_launched", "2026-10-12 01:00:00.000000", "{}", "{\"initial_utm_source\":\"early\"}")));

        assertThat(result.attributes().get(7L))
                .returns("late", UserAttributeObservation::utmSource)
                .returns(Instant.parse("2026-10-12T01:00:00Z"), UserAttributeObservation::firstSeenAt)
                .returns(Instant.parse("2026-10-12T05:00:00Z"), UserAttributeObservation::lastSeenAt);
    }

    @Test
    void 여러_항목을_모두_읽고_gz가_아닌_항목은_무시한다() throws IOException {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        entries.put("123/", List.of());
        entries.put("123/a#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 01:00:00.000000")));
        entries.put("123/b#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 02:00:00.000000")));
        entries.put("123/readme.txt", List.of("ignore me"));

        assertThat(parse(entries).counts()).containsEntry(new DailyEventKey(7, OCT_12, "app_launched"), 2);
    }

    @Test
    void zip_항목이_하나도_없으면_예외다() {
        // 200인데 zip이 아닌 본문(프록시 오류 페이지 등)을 0건으로 처리 기록하면 그날 행동이 영영 0이 된다
        assertThatThrownBy(() -> AmplitudeEventParser.parse(new ByteArrayInputStream("<html>".getBytes())))
                .isInstanceOf(AmplitudeExportException.class);
    }

    @Test
    void event_time은_소수점이_없어도_읽는다() throws IOException {
        WindowAggregate result = parse(List.of(
                event("7", "app_launched", "2026-10-12 01:00:00"),
                event("7", "app_launched", "2026-10-12 01:00:00.123")));

        assertThat(result.counts()).containsEntry(new DailyEventKey(7, OCT_12, "app_launched"), 2);
    }

    @Test
    void 이벤트_키는_120자로_자른다() throws IOException {
        String longName = "x".repeat(130);

        WindowAggregate result = parse(List.of(event("7", longName, "2026-10-12 01:00:00.000000")));

        assertThat(result.counts()).containsOnlyKeys(new DailyEventKey(7, OCT_12, "x".repeat(120)));
    }
}
```

- [ ] **Step 3: 실패 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.AmplitudeEventParserTest"` / Expected: 컴파일 실패

- [ ] **Step 4: 구현**

`DailyEventKey.java`:
```java
package project.study.metrics.amplitude;

import java.time.LocalDate;

/**
 * 적재 횟수의 키.
 *
 * @param userId 우리 DB userId (Amplitude user_id)
 * @param eventDate 이벤트 발생 시각의 KST 날짜
 * @param eventKey event_type 또는 속성값 파생 키("event_type:값")
 */
public record DailyEventKey(long userId, LocalDate eventDate, String eventKey) {}
```

`UserAttributeObservation.java`:
```java
package project.study.metrics.amplitude;

import java.time.Instant;

/**
 * 한 유저에 대해 본 Amplitude 속성.
 *
 * @param utmSource 최초 유입 UTM 소스(없으면 null)
 * @param adClick 광고 클릭 ID를 가지고 들어왔는지
 * @param osName 기기 OS
 * @param firstSeenAt 처음 본 이벤트 발생 시각
 * @param lastSeenAt 마지막으로 본 이벤트 발생 시각
 */
public record UserAttributeObservation(
        String utmSource, boolean adClick, String osName, Instant firstSeenAt, Instant lastSeenAt) {

    /** 두 관측을 합친다 — 값은 늦게 본 쪽의 non-null이 이기고, 광고 클릭은 한 번이라도 있으면 남는다. */
    public UserAttributeObservation merge(UserAttributeObservation other) {
        boolean otherIsLater = other.lastSeenAt.isAfter(lastSeenAt);
        UserAttributeObservation later = otherIsLater ? other : this;
        UserAttributeObservation earlier = otherIsLater ? this : other;
        return new UserAttributeObservation(
                later.utmSource != null ? later.utmSource : earlier.utmSource,
                adClick || other.adClick,
                later.osName != null ? later.osName : earlier.osName,
                firstSeenAt.isBefore(other.firstSeenAt) ? firstSeenAt : other.firstSeenAt,
                later.lastSeenAt);
    }
}
```

`WindowAggregate.java`:
```java
package project.study.metrics.amplitude;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 창 하나의 집계 — 유저×날짜×이벤트 키 횟수와 유저 속성. 파서만 채운다. */
public final class WindowAggregate {

    private final Map<DailyEventKey, Integer> counts = new HashMap<>();
    private final Map<Long, UserAttributeObservation> attributes = new HashMap<>();
    private int eventCount;
    private int skipped;

    public static WindowAggregate empty() {
        return new WindowAggregate();
    }

    void addEvent(long userId, LocalDate eventDate, List<String> eventKeys, UserAttributeObservation attribute) {
        eventCount++;
        for (String eventKey : eventKeys) {
            counts.merge(new DailyEventKey(userId, eventDate, eventKey), 1, Integer::sum);
        }
        attributes.merge(userId, attribute, UserAttributeObservation::merge);
    }

    void skip() {
        skipped++;
    }

    public Map<DailyEventKey, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public Map<Long, UserAttributeObservation> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /** 적재한 이벤트 수(user_id가 있는 것). */
    public int eventCount() {
        return eventCount;
    }

    /** user_id가 없거나 숫자가 아니거나 줄이 깨져 건너뛴 수. */
    public int skipped() {
        return skipped;
    }
}
```

`AmplitudeExportException.java`:
```java
package project.study.metrics.amplitude;

/**
 * Export 다운로드·파싱 실패. 원인(cause)을 붙이지 않는다 — HTTP 예외 체인에 Authorization 헤더나 요청 정보가 실릴 수 있어
 * 상태 코드·예외 클래스 이름만 메시지에 쓴다 ({@code SlackNotificationException}과 같은 이유).
 */
public class AmplitudeExportException extends RuntimeException {

    public AmplitudeExportException(String message) {
        super(message);
    }
}
```

`AmplitudeEventParser.java`:
```java
package project.study.metrics.amplitude;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Export 응답(zip 안에 시간별 gzip JSON 줄 파일)을 줄 단위로 읽어 {@link WindowAggregate}로 모은다. 원본은 메모리에 올리지
 * 않는다 — 한 줄씩 읽고 횟수만 남긴다 (스펙 §7.3).
 */
public final class AmplitudeEventParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Export의 event_time은 UTC `yyyy-MM-dd HH:mm:ss.SSSSSS`. 소수점 자릿수가 달라도 읽는다
    private static final DateTimeFormatter EVENT_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();
    // 광고 클릭 ID가 하나라도 있으면 광고를 눌러 들어온 유저다. Amplitude는 값이 없으면 "EMPTY"를 넣는다
    private static final List<String> AD_CLICK_IDS = List.of(
            "initial_fbclid", "initial_gclid", "initial_gbraid", "initial_wbraid", "initial_dclid",
            "initial_msclkid", "initial_ttclid", "initial_twclid", "initial_li_fat_id", "initial_rdt_cid",
            "initial_ko_click_id");
    private static final String EMPTY = "EMPTY";
    // amplitude_daily_event.event_key 컬럼 길이 — 넘으면 창 전체가 롤백되므로 자른다
    private static final int MAX_EVENT_KEY_LENGTH = 120;

    private AmplitudeEventParser() {}

    public static WindowAggregate parse(InputStream zip) throws IOException {
        WindowAggregate aggregate = WindowAggregate.empty();
        ZipInputStream entries = new ZipInputStream(zip, StandardCharsets.UTF_8);
        int entryCount = 0;
        for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
            entryCount++;
            if (entry.getName().endsWith(".gz")) {
                readLines(new GZIPInputStream(new NonClosingInputStream(entries)), aggregate);
            }
        }
        if (entryCount == 0) {
            throw new AmplitudeExportException("Amplitude Export 응답에 zip 항목이 없다");
        }
        return aggregate;
    }

    private static void readLines(InputStream gzip, WindowAggregate aggregate) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (!line.isBlank()) {
                addLine(line, aggregate);
            }
        }
    }

    private static void addLine(String line, WindowAggregate aggregate) {
        JsonNode event;
        try {
            event = JSON.readTree(line);
        } catch (JacksonException e) {
            aggregate.skip();
            return;
        }
        Long userId = userId(event.path("user_id"));
        String eventType = text(event, "event_type");
        Instant eventTime = eventTime(text(event, "event_time"));
        if (userId == null || eventType == null || eventTime == null) {
            aggregate.skip();
            return;
        }
        aggregate.addEvent(
                userId,
                LocalDate.ofInstant(eventTime, ExportWindow.KST),
                eventKeys(eventType, event.path("event_properties")),
                attribute(event, eventTime));
    }

    /** event_type과, 켜기·가로 모드처럼 값이 의미를 가르는 이벤트는 "event_type:값" 키를 함께 센다 (스펙 §7.3). */
    static List<String> eventKeys(String eventType, JsonNode properties) {
        String type = truncate(eventType);
        String derived = switch (eventType) {
            case "session_simple_mode_toggled", "social_room_camera_toggled" ->
                isTrue(properties.path("on")) ? eventType + ":on" : null;
            case "session_orientation_changed" ->
                "landscape".equals(text(properties, "orientation")) ? eventType + ":landscape" : null;
            default -> null;
        };
        return derived == null ? List.of(type) : List.of(type, derived);
    }

    private static UserAttributeObservation attribute(JsonNode event, Instant eventTime) {
        JsonNode userProperties = event.path("user_properties");
        boolean adClick = AD_CLICK_IDS.stream().anyMatch(key -> present(text(userProperties, key)));
        String utmSource = text(userProperties, "initial_utm_source");
        return new UserAttributeObservation(
                present(utmSource) ? utmSource : null, adClick, text(event, "os_name"), eventTime, eventTime);
    }

    private static Long userId(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (!node.isString()) {
            return null;
        }
        try {
            return Long.parseLong(node.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant eventTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, EVENT_TIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static boolean isTrue(JsonNode node) {
        return node.isBoolean() && node.booleanValue();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank() && !EMPTY.equals(value);
    }

    private static String truncate(String eventKey) {
        return eventKey.length() > MAX_EVENT_KEY_LENGTH ? eventKey.substring(0, MAX_EVENT_KEY_LENGTH) : eventKey;
    }

    /** gzip 리더를 닫아도 바깥 zip 스트림은 닫히지 않게 한다 — 다음 항목을 계속 읽어야 한다. */
    private static final class NonClosingInputStream extends FilterInputStream {

        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // 바깥 ZipInputStream은 parse 호출자가 닫는다
        }
    }
}
```

> 참고: Jackson 3 `JsonNode`의 문자열 판정·읽기는 `isString()`/`asString()`이다(2.x의 `isTextual()`/`asText()`에서 이름이 바뀜, `TaskIdentity` 선례). 컴파일 오류가 나면 이 이름부터 확인한다.

- [ ] **Step 5: 통과 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.AmplitudeEventParserTest"` / Expected: PASS(10)

---

### Task 3: HTTP Export 소스

**Files:**
- Create: `metrics/amplitude/AmplitudeExportSource.java`, `HttpAmplitudeExportSource.java`, `LimitedInputStream.java`, `ExportTooLargeException.java`
- Test: `src/test/java/project/study/metrics/amplitude/HttpAmplitudeExportSourceTest.java`

**Interfaces:**
- Consumes: `ExportWindow`, `AmplitudeEventParser.parse`, `WindowAggregate`, `AmplitudeExportException`
- Produces:
  - `interface AmplitudeExportSource { boolean isEnabled(); WindowAggregate fetch(ExportWindow window); }`
  - `@Component class HttpAmplitudeExportSource implements AmplitudeExportSource` — 생성자 `(RestClient.Builder, @Value base-url, @Value api-key, @Value secret-key, @Value max-download-bytes)`
  - `class ExportTooLargeException extends AmplitudeExportException`

- [ ] **Step 1: 실패하는 테스트**

`HttpAmplitudeExportSourceTest.java`:
```java
package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.metrics.amplitude.ExportZipFixture.event;
import static project.study.metrics.amplitude.ExportZipFixture.zip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpAmplitudeExportSourceTest {

    private static final String API_KEY = "apiKey123";
    private static final String SECRET_KEY = "verySecretAmplitudeKey987";
    private static final ExportWindow WINDOW = new ExportWindow(LocalDate.of(2026, 10, 12));

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpAmplitudeExportSource source(String apiKey, String secretKey, long maxBytes) {
        String baseUrl = server == null ? "http://127.0.0.1:1" : "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpAmplitudeExportSource(RestClient.builder(), baseUrl, apiKey, secretKey, maxBytes);
    }

    private void respond(int status, byte[] body, AtomicReference<HttpExchange> seen) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                seen.set(exchange);
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @Test
    void 창의_수신_시각_범위와_Basic_인증으로_요청해_파싱한다() throws IOException {
        AtomicReference<HttpExchange> seen = new AtomicReference<>();
        respond(200, zip(Map.of("a#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 01:00:00.000000")))),
                seen);

        WindowAggregate result = source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW);

        assertThat(seen.get().getRequestURI().getPath()).isEqualTo("/api/2/export");
        assertThat(seen.get().getRequestURI().getQuery()).isEqualTo("start=20261011T15&end=20261012T14");
        String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((API_KEY + ":" + SECRET_KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(seen.get().getRequestHeaders().getFirst("Authorization")).isEqualTo(expectedAuth);
        assertThat(result.eventCount()).isEqualTo(1);
    }

    @Test
    void 데이터가_없다는_404면_빈_결과다() throws IOException {
        respond(404, new byte[0], new AtomicReference<>());

        WindowAggregate result = source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW);

        assertThat(result.eventCount()).isZero();
        assertThat(result.counts()).isEmpty();
    }

    @Test
    void 서버_오류면_예외이고_메시지에_시크릿이_없다() throws IOException {
        respond(500, "boom".getBytes(), new AtomicReference<>());

        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW))
                .isInstanceOf(AmplitudeExportException.class)
                .hasMessageContaining("500")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_KEY).doesNotContain(API_KEY));
    }

    @Test
    void 연결이_안_되면_예외이고_원인을_붙이지_않는다() {
        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 1_000_000).fetch(WINDOW))
                .isInstanceOf(AmplitudeExportException.class)
                .hasNoCause();
    }

    @Test
    void 응답이_크기_상한을_넘으면_중단한다() throws IOException {
        respond(200, zip(Map.of("a#0.json.gz", List.of(event("7", "app_launched", "2026-10-12 01:00:00.000000")))),
                new AtomicReference<>());

        assertThatThrownBy(() -> source(API_KEY, SECRET_KEY, 10).fetch(WINDOW))
                .isInstanceOf(ExportTooLargeException.class);
    }

    @Test
    void 키가_하나라도_비면_비활성이다() {
        assertThat(source(API_KEY, SECRET_KEY, 1).isEnabled()).isTrue();
        assertThat(source("", SECRET_KEY, 1).isEnabled()).isFalse();
        assertThat(source(API_KEY, "  ", 1).isEnabled()).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.HttpAmplitudeExportSourceTest"` / Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`AmplitudeExportSource.java`:
```java
package project.study.metrics.amplitude;

/** Amplitude Export 창 하나를 받아 온다. 인터페이스로 두어 적재 테스트에서 HTTP 없이 대체한다. */
public interface AmplitudeExportSource {

    /** API 키·시크릿 키가 모두 설정돼 있는지. 비활성이면 적재를 건너뛴다. */
    boolean isEnabled();

    /** 창 하나의 이벤트를 받아 모은다. 데이터가 없으면(404) 빈 결과, 실패면 {@link AmplitudeExportException}. */
    WindowAggregate fetch(ExportWindow window);
}
```

`ExportTooLargeException.java`:
```java
package project.study.metrics.amplitude;

/** Export 응답이 다운로드 상한을 넘었다 — 서버 안 적재를 배치로 분리할 때가 됐다는 신호다 (ADR-0030 결정 7). */
public class ExportTooLargeException extends AmplitudeExportException {

    public ExportTooLargeException(long limitBytes) {
        super("Amplitude Export 응답이 상한(%d바이트)을 넘었다 — 배치 분리 검토 신호 (ADR-0030)".formatted(limitBytes));
    }
}
```

`LimitedInputStream.java`:
```java
package project.study.metrics.amplitude;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 읽은 바이트가 상한을 넘으면 {@link ExportTooLargeException}을 던진다 — 압축 해제 전(전송 바이트) 기준. */
final class LimitedInputStream extends FilterInputStream {

    private final long limitBytes;
    private long readBytes;

    LimitedInputStream(InputStream in, long limitBytes) {
        super(in);
        this.limitBytes = limitBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value != -1) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            count(read);
        }
        return read;
    }

    private void count(long bytes) {
        readBytes += bytes;
        if (readBytes > limitBytes) {
            throw new ExportTooLargeException(limitBytes);
        }
    }
}
```

`HttpAmplitudeExportSource.java`:
```java
package project.study.metrics.amplitude;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Amplitude Export API({@code GET /api/2/export}) 구현. 응답 zip을 받는 동안 줄 단위로 파싱해 메모리에 원본을 올리지 않는다.
 */
@Slf4j
@Component
public class HttpAmplitudeExportSource implements AmplitudeExportSource {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 하루치 zip을 받다 응답이 멈추면 metrics 스레드가 영영 묶여 다음 날 적재·리포트도 못 돈다
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final int NOT_FOUND = 404;

    private final RestClient restClient;
    private final String baseUrl;
    private final String authorization;
    private final long maxDownloadBytes;

    public HttpAmplitudeExportSource(
            RestClient.Builder restClientBuilder,
            @Value("${metrics.amplitude.base-url:https://amplitude.com}") String baseUrl,
            @Value("${metrics.amplitude.api-key:}") String apiKey,
            @Value("${metrics.amplitude.secret-key:}") String secretKey,
            @Value("${metrics.amplitude.max-download-bytes:209715200}") long maxDownloadBytes) {
        // 공유 싱글턴 빌더를 clone()해 이 소스 전용 타임아웃만 얹는다 (SlackWebhookNotifier와 같은 이유)
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient =
                restClientBuilder.clone().requestFactory(requestFactory).build();
        this.baseUrl = baseUrl;
        this.authorization = basic(apiKey, secretKey);
        this.maxDownloadBytes = maxDownloadBytes;
        if (!isEnabled()) {
            log.warn("metrics.amplitude.api-key/secret-key가 비어 있어 Amplitude 적재를 하지 않는다");
        }
    }

    private static String basic(String apiKey, String secretKey) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(secretKey)) {
            return null;
        }
        String credentials = apiKey.trim() + ":" + secretKey.trim();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isEnabled() {
        return authorization != null;
    }

    @Override
    public WindowAggregate fetch(ExportWindow window) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/2/export")
                .queryParam("start", window.startParam())
                .queryParam("end", window.endParam())
                .build()
                .toUri();
        try {
            return restClient
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange((request, response) -> read(response));
        } catch (RestClientException e) {
            throw new AmplitudeExportException(
                    "Amplitude Export 호출 실패 (%s)".formatted(e.getClass().getSimpleName()));
        }
    }

    private WindowAggregate read(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == NOT_FOUND) {
            // Export는 그 기간에 데이터가 없으면 404를 준다 — 0건으로 처리 기록한다 (스펙 §10)
            return WindowAggregate.empty();
        }
        if (!status.is2xxSuccessful()) {
            throw new AmplitudeExportException("Amplitude Export 응답 실패 (HTTP %d)".formatted(status.value()));
        }
        return AmplitudeEventParser.parse(new LimitedInputStream(response.getBody(), maxDownloadBytes));
    }
}
```

> `exchange`의 함수 안에서 던진 `IOException`(깨진 zip 등)은 `RestClient`가 `ResourceAccessException`(= `RestClientException`)으로 감싸 위 catch에 걸린다. `AmplitudeExportException`·`ExportTooLargeException`은 런타임 예외라 그대로 통과한다.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.HttpAmplitudeExportSourceTest"` / Expected: PASS(6)

---

### Task 4: 저장소와 창 단위 쓰기

**Files:**
- Create: `metrics/amplitude/repository/AmplitudeIngestRepository.java`, `metrics/amplitude/AmplitudeWindowWriter.java`
- Test: `src/test/java/project/study/metrics/amplitude/AmplitudeWindowWriterIntegrationTest.java`

**Interfaces:**
- Consumes: `DailyEventKey`, `UserAttributeObservation`, `WindowAggregate`, `ExportWindow`
- Produces:
  - `AmplitudeIngestRepository` — `boolean claimWindow(LocalDate date, int eventCount, int skipped)`, `void addCounts(Map<DailyEventKey, Integer> counts)`, `void upsertAttributes(Map<Long, UserAttributeObservation> attributes)`, `Set<LocalDate> findProcessedDates(LocalDate from, LocalDate to)`
  - `AmplitudeWindowWriter` — `@Transactional boolean write(ExportWindow window, WindowAggregate aggregate)`
  - 테스트 전용 생성 헬퍼가 필요해 `WindowAggregate`에 패키지 전용 `addEvent`를 그대로 쓴다(같은 패키지 테스트).

- [ ] **Step 1: 실패하는 테스트**

`AmplitudeWindowWriterIntegrationTest.java`:
```java
package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AmplitudeWindowWriterIntegrationTest {

    private static final LocalDate OCT_11 = LocalDate.of(2026, 10, 11);
    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);
    private static final Instant T1 = Instant.parse("2026-10-12T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-12T05:00:00Z");

    @Autowired
    private AmplitudeWindowWriter writer;

    @Autowired
    private AmplitudeIngestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static WindowAggregate aggregate(long userId, LocalDate date, String key, int times, Instant seenAt) {
        WindowAggregate aggregate = WindowAggregate.empty();
        for (int i = 0; i < times; i++) {
            aggregate.addEvent(userId, date, List.of(key), new UserAttributeObservation(null, false, "ios", seenAt, seenAt));
        }
        return aggregate;
    }

    private Integer count(long userId, LocalDate date, String key) {
        return jdbcTemplate.queryForObject(
                "select count from amplitude_daily_event where user_id = ? and event_date = ? and event_key = ?",
                Integer.class, userId, date, key);
    }

    @Test
    void 창을_쓰면_처리_기록과_횟수와_속성이_남는다() {
        boolean written = writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));

        assertThat(written).isTrue();
        assertThat(count(7, OCT_12, "app_launched")).isEqualTo(2);
        assertThat(repository.findProcessedDates(OCT_11, OCT_12)).containsExactly(OCT_12);
        assertThat(jdbcTemplate.queryForObject(
                        "select event_count from amplitude_export_window where window_date = ?", Integer.class, OCT_12))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "select os_name from amplitude_user_attribute where user_id = 7", String.class))
                .isEqualTo("ios");
    }

    @Test
    void 같은_창을_두_번_쓰면_두_번째는_아무것도_쓰지_않는다() {
        writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));

        boolean second = writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_12, "app_launched", 2, T1));

        assertThat(second).isFalse();
        assertThat(count(7, OCT_12, "app_launched")).isEqualTo(2);
    }

    @Test
    void 늦게_수신된_이벤트는_다른_창에서_발생일_횟수에_더해진다() {
        writer.write(new ExportWindow(OCT_11), aggregate(7, OCT_11, "app_launched", 1, T1));

        writer.write(new ExportWindow(OCT_12), aggregate(7, OCT_11, "app_launched", 1, T1));

        assertThat(count(7, OCT_11, "app_launched")).isEqualTo(2);
    }

    @Test
    void 유저_속성은_늦게_본_값이_이기고_광고_클릭은_한_번이라도_있으면_남는다() {
        WindowAggregate first = WindowAggregate.empty();
        first.addEvent(7, OCT_11, List.of("app_launched"), new UserAttributeObservation("insta", true, "ios", T1, T1));
        writer.write(new ExportWindow(OCT_11), first);
        WindowAggregate second = WindowAggregate.empty();
        second.addEvent(7, OCT_12, List.of("app_launched"), new UserAttributeObservation("tiktok", false, null, T2, T2));

        writer.write(new ExportWindow(OCT_12), second);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select utm_source, ad_click, os_name, first_seen_at, last_seen_at from amplitude_user_attribute"
                        + " where user_id = 7");
        assertThat(row)
                .containsEntry("utm_source", "tiktok")
                .containsEntry("ad_click", true)
                .containsEntry("os_name", "ios");
        assertThat(((java.sql.Timestamp) row.get("first_seen_at")).toInstant()).isEqualTo(T1);
        assertThat(((java.sql.Timestamp) row.get("last_seen_at")).toInstant()).isEqualTo(T2);
    }

    @Test
    void 이벤트가_없는_창도_처리_기록은_남긴다() {
        writer.write(new ExportWindow(OCT_12), WindowAggregate.empty());

        assertThat(repository.findProcessedDates(OCT_12, OCT_12)).containsExactly(OCT_12);
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.AmplitudeWindowWriterIntegrationTest"` / Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`repository/AmplitudeIngestRepository.java`:
```java
package project.study.metrics.amplitude.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.amplitude.DailyEventKey;
import project.study.metrics.amplitude.UserAttributeObservation;

/** Amplitude 적재 테이블 쓰기·조회 (V28). */
@Repository
@RequiredArgsConstructor
public class AmplitudeIngestRepository {

    private static final String ADD_COUNT = """
            INSERT INTO amplitude_daily_event (user_id, event_date, event_key, count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id, event_date, event_key)
            DO UPDATE SET count = amplitude_daily_event.count + excluded.count""";

    // 값은 늦게 본 쪽의 non-null이 이기고(UserAttributeObservation.merge와 같은 규칙), 광고 클릭은 한 번이라도 있으면 남는다
    private static final String UPSERT_ATTRIBUTE = """
            INSERT INTO amplitude_user_attribute (user_id, utm_source, ad_click, os_name, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                utm_source = CASE WHEN excluded.last_seen_at >= amplitude_user_attribute.last_seen_at
                                  THEN coalesce(excluded.utm_source, amplitude_user_attribute.utm_source)
                                  ELSE coalesce(amplitude_user_attribute.utm_source, excluded.utm_source) END,
                ad_click = amplitude_user_attribute.ad_click OR excluded.ad_click,
                os_name = CASE WHEN excluded.last_seen_at >= amplitude_user_attribute.last_seen_at
                               THEN coalesce(excluded.os_name, amplitude_user_attribute.os_name)
                               ELSE coalesce(amplitude_user_attribute.os_name, excluded.os_name) END,
                first_seen_at = least(amplitude_user_attribute.first_seen_at, excluded.first_seen_at),
                last_seen_at = greatest(amplitude_user_attribute.last_seen_at, excluded.last_seen_at)""";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    /** 창 처리 기록을 선점한다. 이미 있으면(재실행·다른 태스크) false — 호출자는 아무것도 쓰지 않는다. */
    public boolean claimWindow(LocalDate date, int eventCount, int skipped) {
        int inserted = jdbc.sql("""
                        INSERT INTO amplitude_export_window (window_date, event_count, skipped)
                        VALUES (:date, :eventCount, :skipped)
                        ON CONFLICT (window_date) DO NOTHING""")
                .param("date", date)
                .param("eventCount", eventCount)
                .param("skipped", skipped)
                .update();
        return inserted == 1;
    }

    public void addCounts(Map<DailyEventKey, Integer> counts) {
        // 같은 행을 동시에 갱신할 수 있는 다른 트랜잭션과 행 잠금 순서를 맞춘다
        List<Object[]> rows = counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingLong(DailyEventKey::userId)
                        .thenComparing(DailyEventKey::eventDate)
                        .thenComparing(DailyEventKey::eventKey)))
                .map(entry -> new Object[] {
                    entry.getKey().userId(), entry.getKey().eventDate(), entry.getKey().eventKey(), entry.getValue()
                })
                .toList();
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(ADD_COUNT, rows);
        }
    }

    public void upsertAttributes(Map<Long, UserAttributeObservation> attributes) {
        List<Object[]> rows = attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    UserAttributeObservation value = entry.getValue();
                    return new Object[] {
                        entry.getKey(), value.utmSource(), value.adClick(), value.osName(),
                        utc(value.firstSeenAt()), utc(value.lastSeenAt())
                    };
                })
                .toList();
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate(UPSERT_ATTRIBUTE, rows);
        }
    }

    public Set<LocalDate> findProcessedDates(LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("""
                        SELECT window_date FROM amplitude_export_window
                        WHERE window_date BETWEEN :from AND :to""")
                .param("from", from)
                .param("to", to)
                .query(LocalDate.class)
                .list());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
```

`AmplitudeWindowWriter.java`:
```java
package project.study.metrics.amplitude;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

/**
 * 창 하나 = 트랜잭션 하나. 처리 기록을 먼저 선점하고 이미 있으면 아무것도 쓰지 않는다 — 배포 중 태스크 둘이 같은 창을
 * 받아도, 재실행해도 한 번만 센다 (ADR-0030 결정 5). 다운로드는 이 트랜잭션 밖에서 끝낸다(커넥션을 오래 쥐지 않게).
 */
@Component
@RequiredArgsConstructor
public class AmplitudeWindowWriter {

    private final AmplitudeIngestRepository repository;

    @Transactional
    public boolean write(ExportWindow window, WindowAggregate aggregate) {
        if (!repository.claimWindow(window.date(), aggregate.eventCount(), aggregate.skipped())) {
            return false;
        }
        repository.addCounts(aggregate.counts());
        repository.upsertAttributes(aggregate.attributes());
        return true;
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.AmplitudeWindowWriterIntegrationTest"` / Expected: PASS(5)

---

### Task 5: 적재 서비스

**Files:**
- Create: `metrics/amplitude/AmplitudeIngestService.java`
- Test: `src/test/java/project/study/metrics/amplitude/AmplitudeIngestServiceTest.java` (단위 — 저장소·쓰기·소스 Mockito)

**Interfaces:**
- Consumes: `AmplitudeExportSource`, `AmplitudeWindowWriter.write`, `AmplitudeIngestRepository.findProcessedDates`, `ExportWindow`
- Produces: `@Service AmplitudeIngestService` — 생성자 `(AmplitudeExportSource, AmplitudeWindowWriter, AmplitudeIngestRepository, Clock, @Value("${metrics.amplitude.backfill-days:60}") int)`, `IngestResult ingestPending()`, `record IngestResult(int written, int failed)`

- [ ] **Step 1: 실패하는 테스트**

`AmplitudeIngestServiceTest.java`:
```java
package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.amplitude.AmplitudeIngestService.IngestResult;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

@ExtendWith(MockitoExtension.class)
class AmplitudeIngestServiceTest {

    // 2026-10-13 09:00 KST
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_11 = LocalDate.of(2026, 10, 11);
    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);

    @Mock
    private AmplitudeExportSource source;

    @Mock
    private AmplitudeWindowWriter writer;

    @Mock
    private AmplitudeIngestRepository repository;

    private AmplitudeIngestService service(Instant now) {
        return new AmplitudeIngestService(source, writer, repository, Clock.fixed(now, ZoneOffset.UTC), 3);
    }

    @Test
    void 백필_기간의_처리_안_된_창을_오래된_것부터_적재한다() {
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(OCT_10, OCT_12)).thenReturn(Set.of(OCT_11));
        WindowAggregate empty = WindowAggregate.empty();
        when(source.fetch(any())).thenReturn(empty);
        when(writer.write(any(), any())).thenReturn(true);

        IngestResult result = service(NOW).ingestPending();

        InOrder order = inOrder(source);
        order.verify(source).fetch(new ExportWindow(OCT_10));
        order.verify(source).fetch(new ExportWindow(OCT_12));
        verify(source, never()).fetch(new ExportWindow(OCT_11));
        assertThat(result).isEqualTo(new IngestResult(2, 0));
    }

    @Test
    void 아직_준비_안_된_어제_창은_건너뛴다() {
        // 2026-10-13 02:00 KST — 어제 창은 03:00 KST부터 준비된다
        Instant beforeReady = Instant.parse("2026-10-12T17:00:00Z");
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(OCT_10, OCT_12)).thenReturn(Set.of(OCT_10, OCT_11));

        IngestResult result = service(beforeReady).ingestPending();

        verify(source, never()).fetch(any());
        assertThat(result).isEqualTo(new IngestResult(0, 0));
    }

    @Test
    void 창_하나가_실패해도_다음_창을_적재하고_실패를_센다() {
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(OCT_10, OCT_12)).thenReturn(Set.of());
        when(source.fetch(new ExportWindow(OCT_10))).thenReturn(WindowAggregate.empty());
        when(source.fetch(new ExportWindow(OCT_11))).thenThrow(new AmplitudeExportException("HTTP 500"));
        when(source.fetch(new ExportWindow(OCT_12))).thenReturn(WindowAggregate.empty());
        when(writer.write(any(), any())).thenReturn(true);

        IngestResult result = service(NOW).ingestPending();

        assertThat(result).isEqualTo(new IngestResult(2, 1));
        verify(writer, never()).write(eq(new ExportWindow(OCT_11)), any());
    }

    @Test
    void 연속_세_번_실패하면_남은_창을_건너뛴다() {
        AmplitudeIngestService service =
                new AmplitudeIngestService(source, writer, repository, Clock.fixed(NOW, ZoneOffset.UTC), 5);
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(LocalDate.of(2026, 10, 8), OCT_12)).thenReturn(Set.of());
        when(source.fetch(any())).thenThrow(new AmplitudeExportException("timeout"));

        IngestResult result = service.ingestPending();

        assertThat(result).isEqualTo(new IngestResult(0, 3));
    }

    @Test
    void 키가_없으면_아무것도_하지_않는다() {
        when(source.isEnabled()).thenReturn(false);

        IngestResult result = service(NOW).ingestPending();

        assertThat(result).isEqualTo(new IngestResult(0, 0));
        verifyNoInteractions(repository, writer);
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.AmplitudeIngestServiceTest"` / Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`AmplitudeIngestService.java`:
```java
package project.study.metrics.amplitude;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

/**
 * 처리 안 된 Amplitude 창을 오래된 것부터 적재한다. 대상은 {@code [오늘 − backfillDays, 어제]} 중 처리 기록이 없고 준비된
 * 창이다 — 첫 배포는 이 규칙으로 과거를 채우고, 실패한 창은 다음 실행이 다시 집는다 (스펙 §7.2).
 */
@Slf4j
@Service
public class AmplitudeIngestService {

    // 네트워크 장애 때 남은 창마다 타임아웃(최대 60초)을 기다리지 않게 이번 실행을 멈춘다
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final AmplitudeExportSource source;
    private final AmplitudeWindowWriter writer;
    private final AmplitudeIngestRepository repository;
    private final Clock clock;
    private final int backfillDays;

    public AmplitudeIngestService(
            AmplitudeExportSource source,
            AmplitudeWindowWriter writer,
            AmplitudeIngestRepository repository,
            Clock clock,
            @Value("${metrics.amplitude.backfill-days:60}") int backfillDays) {
        this.source = source;
        this.writer = writer;
        this.repository = repository;
        this.clock = clock;
        this.backfillDays = backfillDays;
    }

    public IngestResult ingestPending() {
        if (!source.isEnabled()) {
            log.warn("Amplitude 키가 없어 적재를 건너뛴다");
            return new IngestResult(0, 0);
        }
        Instant now = clock.instant();
        int written = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (ExportWindow window : pendingWindows(now)) {
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log.warn("Amplitude 적재가 연속 {}번 실패해 남은 창은 다음 실행으로 미룬다", consecutiveFailures);
                break;
            }
            if (ingest(window)) {
                written++;
                consecutiveFailures = 0;
            } else {
                failed++;
                consecutiveFailures++;
            }
        }
        log.info("Amplitude 적재 완료 (적재={}, 실패={})", written, failed);
        return new IngestResult(written, failed);
    }

    /** 창 하나를 받아 쓴다. 이미 다른 실행이 쓴 창(선점 실패)도 성공으로 본다. 실패는 Sentry로 올리고 false. */
    private boolean ingest(ExportWindow window) {
        try {
            writer.write(window, source.fetch(window));
            return true;
        } catch (RuntimeException e) {
            log.error("Amplitude 창 적재 실패 (창={})", window.date(), e);
            Sentry.captureException(e);
            return false;
        }
    }

    private List<ExportWindow> pendingWindows(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ExportWindow.KST);
        LocalDate from = today.minusDays(backfillDays);
        Set<LocalDate> processed = repository.findProcessedDates(from, today.minusDays(1));
        return from.datesUntil(today)
                .filter(date -> !processed.contains(date))
                .map(ExportWindow::new)
                .filter(window -> window.isReady(now))
                .toList();
    }

    /**
     * @param written 이번 실행에서 처리한 창 수(선점 실패로 건너뛴 창 포함)
     * @param failed 실패한 창 수
     */
    public record IngestResult(int written, int failed) {}
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.metrics.amplitude.AmplitudeIngestServiceTest"` / Expected: PASS(5)

---

### Task 6: 실행기·스케줄·run-once·설정

**Files:**
- Create: `metrics/job/MetricsJobRunner.java`, `metrics/job/MetricsRunOnce.java`, `metrics/amplitude/AmplitudeIngestJob.java`
- Modify: `src/main/resources/application-prod.yaml`(metrics 절), `src/main/resources/application-local.yaml.example`(metrics·app.report 주석)
- Modify: `src/test/java/project/study/config/FeatureSwitchTest.java`
- Test: `src/test/java/project/study/metrics/job/MetricsJobRunnerTest.java`

**Interfaces:**
- Consumes: `AmplitudeIngestService.ingestPending()`
- Produces:
  - `@Component MetricsJobRunner` — `Future<?> submit(String jobName, Runnable job)` (PR ②의 리포트 잡도 이걸 쓴다)
  - `@Component @ConditionalOnProperty("app.report.enabled"="true") AmplitudeIngestJob` — `@Scheduled(cron="0 0 9 * * *", zone="Asia/Seoul") void ingest()`
  - `@Component @ConditionalOnProperty("app.report.run-once"="true") MetricsRunOnce implements ApplicationRunner`

- [ ] **Step 1: 실패하는 테스트**

`MetricsJobRunnerTest.java`:
```java
package project.study.metrics.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetricsJobRunnerTest {

    private final MetricsJobRunner runner = new MetricsJobRunner();

    @AfterEach
    void stop() {
        runner.shutdown();
    }

    @Test
    void 제출한_작업을_순서대로_한_스레드에서_돌린다() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();

        runner.submit("a", () -> ran.add("a:" + Thread.currentThread().getName()));
        runner.submit("b", () -> ran.add("b:" + Thread.currentThread().getName()))
                .get(5, TimeUnit.SECONDS);

        assertThat(ran).containsExactly("a:metrics-job", "b:metrics-job");
    }

    @Test
    void 작업이_실패해도_예외가_밖으로_나가지_않고_다음_작업은_돈다() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();

        assertThatCode(() -> runner.submit("boom", () -> {
                            throw new IllegalStateException("boom");
                        })
                        .get(5, TimeUnit.SECONDS))
                .doesNotThrowAnyException();
        runner.submit("after", () -> ran.add("after")).get(5, TimeUnit.SECONDS);

        assertThat(ran).containsExactly("after");
    }
}
```

`FeatureSwitchTest.java`에 추가(기존 `report` 러너 옆):
```java
    private final ApplicationContextRunner amplitudeIngest = new ApplicationContextRunner()
            .withBean(MetricsJobRunner.class, () -> mock(MetricsJobRunner.class))
            .withBean(AmplitudeIngestService.class, () -> mock(AmplitudeIngestService.class))
            .withUserConfiguration(AmplitudeIngestJob.class, MetricsRunOnce.class);

    @Test
    void Amplitude_적재_잡은_리포트_스위치가_없으면_뜨지_않는다() {
        amplitudeIngest.run(context -> assertThat(context).doesNotHaveBean(AmplitudeIngestJob.class));
    }

    @Test
    void Amplitude_적재_잡은_리포트_스위치를_켜면_뜬다() {
        amplitudeIngest
                .withPropertyValues("app.report.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(AmplitudeIngestJob.class));
    }

    @Test
    void 한_번_실행은_값이_없으면_꺼져_있다() {
        amplitudeIngest
                .withPropertyValues("app.report.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(MetricsRunOnce.class));
    }

    @Test
    void 한_번_실행은_켜면_뜬다() {
        amplitudeIngest
                .withPropertyValues("app.report.run-once=true")
                .run(context -> assertThat(context).hasSingleBean(MetricsRunOnce.class));
    }
```
(import: `project.study.metrics.amplitude.AmplitudeIngestJob`, `project.study.metrics.amplitude.AmplitudeIngestService`, `project.study.metrics.job.MetricsJobRunner`, `project.study.metrics.job.MetricsRunOnce`)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests "project.study.metrics.job.*" --tests "project.study.config.FeatureSwitchTest"` / Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`MetricsJobRunner.java`:
```java
package project.study.metrics.job;

import io.sentry.Sentry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 지표 작업(Amplitude 적재·일일 리포트)을 도는 단일 스레드. 스케줄 메서드는 여기에 제출만 하고 바로 돌아간다 — prod 스케줄러
 * 풀(2개)을 몇 분씩 붙잡아 룸 정리·랭킹 마감이 밀리지 않게 (ADR-0030 결정 7).
 *
 * <p>실행기를 {@code TaskScheduler}·{@code Executor} 타입 빈으로 만들지 않는다. 그런 빈이 생기면 Boot의 기본 스케줄러·실행기
 * 자동 구성이 물러나 다른 {@code @Scheduled}까지 이 스레드로 돈다.
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — 실행기 안에서 난 예외는 {@code Future}에 갇혀 아무 데도 남지 않는다.
 */
@Slf4j
@Component
public class MetricsJobRunner {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("metrics-job").daemon(true).factory());

    public Future<?> submit(String jobName, Runnable job) {
        return executor.submit(() -> {
            try {
                job.run();
            } catch (Exception e) {
                log.error("지표 작업 실패 (작업={})", jobName, e);
                Sentry.captureException(e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
```

`AmplitudeIngestJob.java`:
```java
package project.study.metrics.amplitude;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.metrics.job.MetricsJobRunner;

/**
 * {@code app.report.enabled=true}(prod yaml)일 때만 뜬다 — 리포트와 같은 스위치다. 적재만 따로 켤 일이 없고, 리포트가
 * 이 데이터를 읽는다.
 */
@Component
@ConditionalOnProperty(name = "app.report.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AmplitudeIngestJob {

    private final MetricsJobRunner runner;
    private final AmplitudeIngestService ingestService;

    /**
     * 매일 09:00(KST). 어제 창은 오늘 03:00(KST)부터 준비되므로 10시 리포트 전에 어제까지 채운다.
     *
     * <p>zone을 명시하는 이유: ECS 태스크는 UTC로 뜬다.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void ingest() {
        runner.submit("amplitude-ingest", ingestService::ingestPending);
    }
}
```

`MetricsRunOnce.java`:
```java
package project.study.metrics.job;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import project.study.metrics.amplitude.AmplitudeIngestService;

/**
 * 로컬 확인용 — {@code app.report.run-once=true}면 기동 직후 지표 작업을 한 번 돌린다. 값이 없으면 꺼짐이 기본이고(BY-640),
 * 로컬 yaml에서만 켠다.
 */
@Component
@ConditionalOnProperty(name = "app.report.run-once", havingValue = "true")
@RequiredArgsConstructor
public class MetricsRunOnce implements ApplicationRunner {

    private final MetricsJobRunner runner;
    private final AmplitudeIngestService ingestService;

    @Override
    public void run(ApplicationArguments args) {
        runner.submit("amplitude-ingest(run-once)", ingestService::ingestPending);
    }
}
```

`application-prod.yaml` — 기존 `metrics:` 절을 다음으로 바꾼다:
```yaml
# 일일 지표 리포트를 보낼 Slack Incoming Webhook.
# URL이 없으면 발송기가 비활성으로 동작한다 — Sentry DSN과 같은 이유로 기본값을 둔다:
# 모니터링 설정 하나가 없다고 API 전체가 기동 실패하는 쪽이 더 나쁘다.
# ECS 태스크 정의가 SSM Parameter Store 값을 SLACK_WEBHOOK_URL로 주입한다.
metrics:
  slack:
    webhook-url: ${SLACK_WEBHOOK_URL:}
  # Amplitude Export 일 단위 적재 (BY-904, ADR-0030). 키가 없으면 적재가 비활성이고 리포트는 Amplitude 항목을 "미수집"으로 쓴다.
  # prod 프로젝트 키를 SSM → ECS 태스크 정의로 주입한다
  amplitude:
    api-key: ${AMPLITUDE_API_KEY:}
    secret-key: ${AMPLITUDE_SECRET_KEY:}
    backfill-days: 60
```

`application-local.yaml.example` — 끝에 추가:
```yaml
# 일일 리포트·Amplitude 적재를 로컬에서 한 번 돌려 보려면 키를 넣고 run-once를 켠다 (BY-904).
# 키는 이 파일(gitignore)에만 둔다
# metrics:
#   amplitude:
#     api-key:
#     secret-key:
#     backfill-days: 3
# app:
#   report:
#     run-once: true
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests "project.study.metrics.job.*" --tests "project.study.config.FeatureSwitchTest"` / Expected: PASS

---

### Task 7: 전체 검증 → 퀴즈 → 커밋 → PR ①

**Files:** 스펙 헤더(`상태:` 줄)에 이 계획 경로 반영, ADR-0030(이미 작성됨)

- [ ] **Step 1: 포맷·전체 검증**

Run: `./gradlew spotlessApply && ./gradlew check`
Expected: BUILD SUCCESSFUL (테스트·spotlessCheck·checkstyle·ArchUnit)

- [ ] **Step 2: 로컬 실측(가능하면)** — 사용자가 로컬 yaml에 키를 넣었으면 `backfill-days: 3`, `run-once: true`로 `./gradlew bootRun --args='--spring.profiles.active=local'` 후 `psql`로 `amplitude_export_window`·`amplitude_daily_event` 행 수 확인. 키가 없으면 건너뛰고 PR 본문에 적는다.

- [ ] **Step 3: Codex 2차 리뷰** — `codex exec -s read-only "<변경 요약 + git diff origin/dev...HEAD 리뷰 요청>"` (메모리: `/codex` 스킬 없음). P1이면 고치고 Step 1부터.

- [ ] **Step 4: 퀴즈 게이트** — 구현 흐름 퀴즈 5문제(창 경계, 선점, 미수집 처리, 실행기 빈 금지 이유, 404 vs 200-빈-zip). 다 맞을 때까지 다른 문제.

- [ ] **Step 5: 목적별 커밋**

```bash
git add src/main/resources/db/migration/V28__amplitude_ingest.sql \
  src/main/java/project/study/metrics/amplitude src/test/java/project/study/metrics/amplitude
git commit -m "feat: Amplitude Export를 창 단위로 적재한다 (BY-904)"
git add src/main/java/project/study/metrics/job src/test/java/project/study/metrics/job \
  src/test/java/project/study/config/FeatureSwitchTest.java \
  src/main/resources/application-prod.yaml src/main/resources/application-local.yaml.example
git commit -m "feat: 지표 작업 전용 실행기와 09시 적재 스케줄을 추가한다 (BY-904)"
git add docs/adr/0030-daily-report-behavioral-cohorts-and-amplitude-ingest.md docs/superpowers
git commit -m "docs: ADR-0030과 PR ① 구현 계획을 추가한다 (BY-904)"
```
(각 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`)

- [ ] **Step 6: PR ①** — `git push -u origin feature/BY-904-daily-report-v2` → `gh pr create --base dev --title "[feat] BY-904 Amplitude 이벤트를 매일 적재한다"`. 본문은 `.github/pull_request_template.md` 절 구조, 배포 전 필요한 것(prod `AMPLITUDE_API_KEY`·`AMPLITUDE_SECRET_KEY`)을 명시.
