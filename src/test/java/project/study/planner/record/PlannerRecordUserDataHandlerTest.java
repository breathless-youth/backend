package project.study.planner.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.user.account.UserDataHandler;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PlannerRecordUserDataHandlerTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private List<UserDataHandler> handlers;

    @Autowired
    private PlannerRecordService records;

    @Test
    void mergeMovesPersonalRecordsAndKeepsTargetOperationOnCollision() {
        long source = user();
        long target = user();
        edit(source, "m:2026-10-01T10:00:00Z", "MANUAL", "10:00", "11:00", false);
        operation(source, "retry", "source");
        operation(target, "retry", "target");
        handler().merge(source, target);
        assertThat(records.records(target, at("00:00"), at("23:00"))).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT result_json FROM planner_record_operation WHERE user_id=? AND operation_id='retry'",
                        String.class,
                        target))
                .isEqualTo("target");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, source))
                .isZero();
    }

    @Test
    void mergeDropsOverlappingSourceAndOrphanCameraOverride() {
        long source = user();
        long target = user();
        edit(source, "m:2026-10-01T10:00:00Z", "MANUAL", "10:00", "11:00", false);
        edit(target, "m:2026-10-01T10:30:00Z", "MANUAL", "10:30", "11:30", false);
        edit(source, "c:2026-10-01T12:00:00Z", "MEASURED", "12:00", "13:00", false);
        handler().merge(source, target);
        assertThat(records.records(target, at("00:00"), at("23:00")))
                .singleElement()
                .satisfies(record -> assertThat(record.key()).isEqualTo("m:2026-10-01T10:30:00Z"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, source))
                .isZero();
    }

    @Test
    void incomingOriginalIsHiddenWhenTargetPersonalRecordOverlaps() {
        long source = user();
        long target = user();
        jdbc.update("""
                INSERT INTO study_session(user_id,stat_date,source,started_at,submission_started_at,ended_at,study_sec,focus_sec)
                VALUES(?,DATE '2026-10-01','MANUAL',?,?,?,3600,3600)
                """, source, Timestamp.from(at("10:00")), Timestamp.from(at("10:00")), Timestamp.from(at("11:00")));
        edit(target, "m:2026-10-01T10:30:00Z", "MANUAL", "10:30", "11:30", false);
        handler().merge(source, target);
        handlers.stream()
                .filter(h -> h.columns().contains("study_session.user_id"))
                .findFirst()
                .orElseThrow()
                .merge(source, target);
        assertThat(records.records(target, at("00:00"), at("23:00")))
                .singleElement()
                .satisfies(record -> assertThat(record.key()).isEqualTo("m:2026-10-01T10:30:00Z"));
    }

    @Test
    void eraseRemovesEveryNewUserColumn() {
        long source = user();
        edit(source, "m:2026-10-01T10:00:00Z", "MANUAL", "10:00", "11:00", false);
        operation(source, "retry", "source");
        handler().erase(source);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, source))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_operation WHERE user_id=?", Integer.class, source))
                .isZero();
    }

    private UserDataHandler handler() {
        return handlers.stream()
                .filter(h ->
                        h.columns().equals(Set.of("planner_record_edit.user_id", "planner_record_operation.user_id")))
                .findFirst()
                .orElseThrow();
    }

    private long user() {
        return jdbc.queryForObject(
                "INSERT INTO users(provider,provider_user_id,nickname) VALUES('test',?,?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    private void edit(long user, String key, String source, String from, String to, boolean deleted) {
        jdbc.update(
                "INSERT INTO planner_record_edit(user_id,record_key,source,started_at,ended_at,deleted) VALUES(?,?,?,?,?,?)",
                user,
                key,
                source,
                Timestamp.from(at(from)),
                Timestamp.from(at(to)),
                deleted);
    }

    private void operation(long user, String id, String result) {
        jdbc.update(
                "INSERT INTO planner_record_operation(user_id,operation_id,payload_hash,result_json) VALUES(?,?,?,?)",
                user,
                id,
                "hash",
                result);
    }

    private static Instant at(String time) {
        return Instant.parse("2026-10-01T" + time + ":00Z");
    }
}
