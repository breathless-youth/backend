package project.study.planner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlannerRecordReadTest {
    @Autowired
    private PlannerRecordService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvcTester mvc;

    private long user;

    @BeforeEach
    void setup() {
        user = jdbc.queryForObject(
                "INSERT INTO users(provider,provider_user_id,nickname) VALUES('test',?,?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    @Test
    void clippingWholeStudySecondsDoesNotLoseSecondsToFloatingPoint() {
        session("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 3600, 3600);
        assertThat(service.records(user, at("10:00"), Instant.parse("2026-10-01T10:01:55Z")))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.studySec()).isEqualTo(115);
                    assertThat(record.focusSec()).isEqualTo(115);
                });
    }

    @Test
    void clippingUsesEventPositionRatherThanUniformDurationRatio() {
        long id = session("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 3600, 3000);
        event(id, "PHONE", "2026-10-01T10:20:00Z", "2026-10-01T10:30:00Z");
        PlannerRecordView record =
                service.records(user, at("10:25"), at("10:45")).getFirst();
        assertThat(record.key()).isEqualTo("c:2026-10-01T10:00:00Z");
        assertThat(record.startedAt()).isEqualTo(at("10:25"));
        assertThat(record.endedAt()).isEqualTo(at("10:45"));
        assertThat(record.studySec()).isEqualTo(1200);
        assertThat(record.focusSec()).isEqualTo(900);
    }

    @Test
    void editingClipsOriginalEventsAndCountsAddedTimeAsFocused() {
        long id = session("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 3000, 2400);
        event(id, "PHONE", "2026-10-01T10:20:00Z", "2026-10-01T10:30:00Z");
        event(id, "PAUSE", "2026-10-01T10:40:00Z", "2026-10-01T10:50:00Z");
        long subject = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES(?,'math') RETURNING id", Long.class, user);
        edit("UPDATE", "10:25", "11:15", subject);
        PlannerRecordView record =
                service.records(user, at("10:00"), at("12:00")).getFirst();
        assertThat(record.studySec()).isEqualTo(2400);
        assertThat(record.focusSec()).isEqualTo(2100);
        assertThat(record.subjectSegments())
                .containsExactly(new PlannerRecordSegment(subject, at("10:25"), at("11:15"), 2400, 2100));
        PlannerRecordView clipped =
                service.records(user, at("11:00"), at("12:00")).getFirst();
        assertThat(clipped.studySec()).isEqualTo(900);
        assertThat(clipped.focusSec()).isEqualTo(900);
    }

    @Test
    void subjectOnlyEditsPreserveCurrentPersonalTotalsAcrossTimingChanges() {
        long id = session("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 3600, 1200);
        event(id, "PHONE", "2026-10-01T10:20:00Z", "2026-10-01T10:30:00Z");
        long math = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES(?,'math') RETURNING id", Long.class, user);
        long english = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES(?,'english') RETURNING id", Long.class, user);
        edit("UPDATE", "10:00", "11:00", math);
        assertThat(service.records(user, at("10:00"), at("12:00")))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.studySec()).isEqualTo(3600);
                    assertThat(record.focusSec()).isEqualTo(1200);
                    assertThat(record.subjectSegments())
                            .containsExactly(new PlannerRecordSegment(math, at("10:00"), at("11:00"), 3600, 1200));
                });
        edit("UPDATE", "10:25", "11:15", math);
        edit("UPDATE", "10:25", "11:15", english);
        assertThat(service.records(user, at("10:00"), at("12:00")))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.studySec()).isEqualTo(3000);
                    assertThat(record.focusSec()).isEqualTo(2700);
                    assertThat(record.subjectSegments())
                            .containsExactly(new PlannerRecordSegment(english, at("10:25"), at("11:15"), 3000, 2700));
                });
    }

    @Test
    void movedOverrideAppearsOnlyAtNewTimeAndDeletionHidesIt() {
        session("2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 3600, 3000);
        edit("UPDATE", "13:00", "14:00", null);
        assertThat(service.records(user, at("10:00"), at("11:00"))).isEmpty();
        assertThat(service.records(user, at("13:00"), at("14:00")))
                .singleElement()
                .satisfies(record -> assertThat(record.focusSec()).isEqualTo(3600));
        edit("DELETE", null, null, null);
        assertThat(service.records(user, at("00:00"), at("23:59"))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM study_session WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    void legacyOverTwentyFourHourOriginalCanStillBeDeleted() {
        session("2026-10-01T10:00:00Z", "2026-10-03T11:00:00Z", 176400, 176400);
        edit("DELETE", null, null, null);
        assertThat(service.records(user, at("00:00"), Instant.parse("2026-10-04T00:00:00Z")))
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT focus_sec FROM study_session WHERE user_id=?", Integer.class, user))
                .isEqualTo(176400);
    }

    @Test
    void legacyMissingEndUsesStoredStudyDurationAndItsOwnKey() {
        long id = session("2026-10-01T10:00:00Z", null, 3600, 3000);
        jdbc.update("UPDATE study_session SET submission_started_at=NULL WHERE id=?", id);
        assertThat(service.records(user, at("10:30"), at("12:00")))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.key()).isEqualTo("c:id:" + id);
                    assertThat(record.endedAt()).isEqualTo(at("11:00"));
                    assertThat(record.studySec()).isEqualTo(1800);
                    assertThat(record.focusSec()).isEqualTo(1500);
                });
    }

    private void edit(String action, String from, String to, Long subject) {
        String body = """
                {"operations":[{"operationId":"%s","action":"%s","key":"c:2026-10-01T10:00:00Z",
                "startedAt":%s,"endedAt":%s,"subjectId":%s}]}
                """.formatted(UUID.randomUUID(), action, json(from), json(to), subject);
        assertThat(mvc.post()
                        .uri("/api/planner/records/batch")
                        .header("API-Version", "1")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.results[0].success", v -> assertThat(v).isEqualTo(true));
    }

    private static String json(String time) {
        return time == null ? "null" : "\"" + at(time) + "\"";
    }

    private static Instant at(String time) {
        return Instant.parse("2026-10-01T" + time + ":00Z");
    }

    private long session(String from, String to, int study, int focus) {
        return jdbc.queryForObject(
                """
                INSERT INTO study_session(user_id,stat_date,started_at,submission_started_at,ended_at,study_sec,focus_sec)
                VALUES(?,DATE '2026-10-01',?,?,?,?,?) RETURNING id
                """,
                Long.class,
                user,
                Timestamp.from(Instant.parse(from)),
                Timestamp.from(Instant.parse(from)),
                to == null ? null : Timestamp.from(Instant.parse(to)),
                study,
                focus);
    }

    private void event(long id, String status, String from, String to) {
        jdbc.update(
                "INSERT INTO status_event(session_id,status,started_at,ended_at) VALUES(?,?,?,?)",
                id,
                status,
                Timestamp.from(Instant.parse(from)),
                Timestamp.from(Instant.parse(to)));
    }
}
