package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V52의 실제 스키마·기존 데이터를 V55까지 올린다. Spring의 이미 이전된 DB와 분리한다. */
class PlannerMigrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final Instant SEOUL_BEFORE_FIVE = Instant.parse("2025-03-01T19:59:00Z");
    private static final Instant SEOUL_AT_FIVE = Instant.parse("2025-03-01T20:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2025-03-03T10:00:00Z");

    private DataSource dataSource;
    private String schema;
    private JdbcClient jdbc;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createLegacySchema() {
        schema = "planner_migration_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource source =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        source.setSchema(schema);
        dataSource = source;
        jdbc = JdbcClient.create(source);
        flyway("52").migrate();
    }

    @Test
    void 완료_날짜를_현지_05시로_옮기고_기존_행과_실제_세션_완료_참조를_보존한다() {
        long seoul = seedUser("Asia/Seoul");
        long newYork = seedUser("America/New_York");
        long subject = seedSubject(seoul, "서울 과목", 7);
        long newYorkSubject = seedSubject(newYork, "뉴욕 과목", 2);
        long deletedOnlySubject = seedSubject(seoul, "삭제된 할 일만 있는 과목", 9);
        long before = seedTask(subject, "05시 전 완료", SEOUL_BEFORE_FIVE, null);
        long atFive = seedTask(subject, "05시 완료", SEOUL_AT_FIVE, null);
        seedTask(subject, "같은 날짜의 두 번째 완료", SEOUL_BEFORE_FIVE.minusSeconds(60), null);
        long deleted = seedTask(deletedOnlySubject, "지운 완료 이력", SEOUL_BEFORE_FIVE, DELETED_AT);
        long dstBefore = seedTask(newYorkSubject, "DST 04:59", Instant.parse("2025-03-09T08:59:00Z"), null);
        long dstAtFive = seedTask(newYorkSubject, "DST 05:00", Instant.parse("2025-03-09T09:00:00Z"), null);
        seedCompletionHistory(seoul, subject, before, atFive, deleted);
        var originalTasks = legacyTaskSnapshot();
        var originalSessions = rows("SELECT * FROM study_session ORDER BY id");
        var originalTaskHistory = rows("SELECT * FROM study_session_task_done ORDER BY session_id, task_id");
        var originalSubjectHistory = rows("SELECT * FROM daily_completion ORDER BY id");

        flyway("55").migrate();

        assertThat(legacyTaskSnapshot()).isEqualTo(originalTasks);
        assertThat(rows("SELECT * FROM study_session ORDER BY id")).isEqualTo(originalSessions);
        assertThat(rows("SELECT * FROM study_session_task_done ORDER BY session_id, task_id"))
                .hasSize(3)
                .isEqualTo(originalTaskHistory);
        assertThat(rows("SELECT * FROM daily_completion ORDER BY id")).isEqualTo(originalSubjectHistory);
        assertThat(plannedDate(before)).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(plannedDate(atFive)).isEqualTo(LocalDate.of(2025, 3, 2));
        assertThat(plannedDate(deleted)).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(plannedDate(dstBefore)).isEqualTo(LocalDate.of(2025, 3, 8));
        assertThat(plannedDate(dstAtFive)).isEqualTo(LocalDate.of(2025, 3, 9));
        assertThat(choiceDates(subject)).containsExactly(LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 2));
        assertThat(choiceDates(deletedOnlySubject)).isEmpty();
        assertThat(jdbc.sql("SELECT DISTINCT sort_order FROM planner_day_subject WHERE subject_id = :s")
                        .param("s", subject)
                        .query(Integer.class)
                        .list())
                .containsExactly(7);
    }

    @Test
    void 미완료는_이전_시점의_사용자_현지_05시_날짜이며_재실행은_중복을_만들지_않는다() {
        long east = seedUser("Pacific/Kiritimati");
        long west = seedUser("Pacific/Honolulu");
        long eastSubject = seedSubject(east, "동쪽", 0);
        long westSubject = seedSubject(west, "서쪽", 0);
        long eastTask = seedTask(eastSubject, "오래된 미완료", null, null);
        long westTask = seedTask(westSubject, "오래된 미완료", null, null);
        seedTask(eastSubject, "같은 과목의 미완료", null, null);
        Instant before = databaseNow();

        flyway("55").migrate();

        Instant after = databaseNow();
        assertCurrentPlanDate(eastTask, "Pacific/Kiritimati", before, after);
        assertCurrentPlanDate(westTask, "Pacific/Honolulu", before, after);
        assertThat(plannedDate(eastTask)).isEqualTo(plannedDate(westTask).plusDays(1));
        assertThat(choiceDates(eastSubject)).containsExactly(plannedDate(eastTask));
        assertThat(choiceDates(westSubject)).containsExactly(plannedDate(westTask));
        var tasksBeforeRerun = rows("SELECT * FROM study_task ORDER BY id");
        var choicesBeforeRerun = rows("SELECT * FROM planner_day_subject ORDER BY user_id, plan_date, subject_id");

        assertThat(flyway("55").migrate().migrationsExecuted).isZero();

        assertThat(rows("SELECT * FROM study_task ORDER BY id")).isEqualTo(tasksBeforeRerun);
        assertThat(rows("SELECT * FROM planner_day_subject ORDER BY user_id, plan_date, subject_id"))
                .isEqualTo(choicesBeforeRerun);
    }

    @Test
    void 구_앱의_날짜_없는_새_행도_사용자_시간대로_채우고_명시한_계획_날짜는_보존한다() {
        flyway("55").migrate();
        long seoul = seedUser("Asia/Seoul");
        long newYork = seedUser("America/New_York");
        long east = seedUser("Pacific/Kiritimati");
        long west = seedUser("Pacific/Honolulu");
        long seoulSubject = seedSubject(seoul, "서울", 0);
        long newYorkSubject = seedSubject(newYork, "뉴욕", 0);
        long eastSubject = seedSubject(east, "동쪽", 0);
        long westSubject = seedSubject(west, "서쪽", 0);
        long beforeFive = seedTask(seoulSubject, "구 앱 완료", SEOUL_BEFORE_FIVE, null);
        long atFive = seedTask(seoulSubject, "구 앱 완료", SEOUL_AT_FIVE, null);
        long dst = seedTask(newYorkSubject, "DST 완료", Instant.parse("2025-03-09T08:59:00Z"), null);
        Instant before = databaseNow();
        long eastTask = seedTask(eastSubject, "구 앱 미완료", null, null);
        long westTask = seedTask(westSubject, "구 앱 미완료", null, null);
        Instant after = databaseNow();
        long explicit = jdbc.sql("""
                        INSERT INTO study_task (subject_id, name, done_at, planned_date)
                        VALUES (:s, '명시한 날짜', :done, DATE '2099-01-01') RETURNING id
                        """)
                .param("s", seoulSubject)
                .param("done", Timestamp.from(SEOUL_BEFORE_FIVE))
                .query(Long.class)
                .single();

        assertThat(plannedDate(beforeFive)).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(plannedDate(atFive)).isEqualTo(LocalDate.of(2025, 3, 2));
        assertThat(plannedDate(dst)).isEqualTo(LocalDate.of(2025, 3, 8));
        assertThat(plannedDate(explicit)).isEqualTo(LocalDate.of(2099, 1, 1));
        assertCurrentPlanDate(eastTask, "Pacific/Kiritimati", before, after);
        assertCurrentPlanDate(westTask, "Pacific/Honolulu", before, after);
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private long seedUser(String timeZone) {
        return jdbc.sql("""
                        INSERT INTO users (provider, provider_user_id, nickname, time_zone)
                        VALUES ('test', :p, :n, :z) RETURNING id
                        """)
                .param("p", UUID.randomUUID().toString())
                .param("n", "migration-" + UUID.randomUUID())
                .param("z", timeZone)
                .query(Long.class)
                .single();
    }

    private long seedSubject(long userId, String name, int sortOrder) {
        return jdbc.sql("INSERT INTO study_subject (user_id, name, sort_order) VALUES (:u, :n, :o) RETURNING id")
                .param("u", userId)
                .param("n", name)
                .param("o", sortOrder)
                .query(Long.class)
                .single();
    }

    private long seedTask(long subjectId, String name, Instant doneAt, Instant deletedAt) {
        return jdbc.sql("""
                        INSERT INTO study_task (subject_id, name, done_at, deleted_at, created_at, updated_at)
                        VALUES (:s, :n, :done, :deleted, TIMESTAMPTZ '2020-01-01 00:00:00Z',
                                TIMESTAMPTZ '2025-03-03 10:00:00Z') RETURNING id
                        """)
                .param("s", subjectId)
                .param("n", name)
                .param("done", doneAt == null ? null : Timestamp.from(doneAt), Types.TIMESTAMP)
                .param("deleted", deletedAt == null ? null : Timestamp.from(deletedAt), Types.TIMESTAMP)
                .query(Long.class)
                .single();
    }

    private void seedCompletionHistory(
            long userId, long subjectId, long beforeTask, long atFiveTask, long deletedTask) {
        long sessionId = jdbc.sql("""
                        INSERT INTO study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec)
                        VALUES (:u, DATE '2025-03-02', :s, :e, 1200, 600) RETURNING id
                        """)
                .param("u", userId)
                .param("s", Timestamp.from(SEOUL_BEFORE_FIVE.minusSeconds(540)))
                .param("e", Timestamp.from(SEOUL_AT_FIVE.plusSeconds(600)))
                .query(Long.class)
                .single();
        for (long task : new long[] {beforeTask, atFiveTask, deletedTask}) {
            jdbc.sql("INSERT INTO study_session_task_done (session_id, task_id) VALUES (:s, :t)")
                    .param("s", sessionId)
                    .param("t", task)
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO daily_completion (user_id, target_type, target_id, completed_date, completed_at)
                        VALUES (:u, 'SUBJECT', :s, DATE '2025-03-01', :at)
                        """)
                .param("u", userId)
                .param("s", subjectId)
                .param("at", Timestamp.from(SEOUL_BEFORE_FIVE))
                .update();
    }

    private LocalDate plannedDate(long taskId) {
        return jdbc.sql("SELECT planned_date FROM study_task WHERE id = :t")
                .param("t", taskId)
                .query(LocalDate.class)
                .single();
    }

    private List<LocalDate> choiceDates(long subjectId) {
        return jdbc.sql("SELECT plan_date FROM planner_day_subject WHERE subject_id = :s ORDER BY plan_date")
                .param("s", subjectId)
                .query(LocalDate.class)
                .list();
    }

    private Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()")
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();
    }

    private void assertCurrentPlanDate(long taskId, String zone, Instant before, Instant after) {
        assertThat(plannedDate(taskId)).isIn(localPlanDate(before, zone), localPlanDate(after, zone));
    }

    private LocalDate localPlanDate(Instant instant, String zone) {
        var local = instant.atZone(ZoneId.of(zone));
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(LocalTime.of(5, 0)) ? date.minusDays(1) : date;
    }

    private List<Map<String, Object>> legacyTaskSnapshot() {
        return rows(
                "SELECT id, subject_id, name, done_at, deleted_at, created_at, updated_at FROM study_task ORDER BY id");
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.sql(sql).query().listOfRows();
    }
}
