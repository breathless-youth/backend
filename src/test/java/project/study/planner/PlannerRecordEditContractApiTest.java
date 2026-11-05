package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.studysession.repository.StudySessionRankingQueries;
import tools.jackson.databind.JsonNode;

class PlannerRecordEditContractApiTest extends PlannerApiSupport {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);
    private static final Instant START = Instant.parse("2026-10-03T19:30:12.123456Z");
    private static final Instant END = Instant.parse("2026-10-03T21:00:45.654321Z");

    @Autowired
    StudySessionRankingQueries rankings;

    private long seed(String source, Instant start, Instant end, int study, int focus) {
        long id = jdbc.queryForObject(
                "INSERT INTO study_session(user_id,stat_date,started_at,submission_started_at,ended_at,study_sec,focus_sec,source) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                user,
                start.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                Timestamp.from(start),
                Timestamp.from(start),
                Timestamp.from(end),
                study,
                focus,
                source);
        jdbc.update(
                "INSERT INTO study_session_subject_segment(session_id,subject_id,started_at,ended_at,study_sec,focus_sec) VALUES (?,?,?,?,?,?)",
                id,
                subject,
                Timestamp.from(start),
                Timestamp.from(end),
                study,
                focus);
        return id;
    }

    private long otherSubject() {
        return jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?, '영어') RETURNING id", Long.class, user);
    }

    private JsonNode save(String key, Instant start, Instant end, long target) {
        String operation = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"action\":\"UPDATE\",\"key\":\"" + key
                + "\",\"subjectId\":" + target + (start == null ? "" : ",\"startedAt\":\"" + start + "\"")
                + (end == null ? "" : ",\"endedAt\":\"" + end + "\"") + "}";
        return call("POST", "/api/planner/records/batch", "{\"operations\":[" + operation + "]}", 200)
                .at("/results/0");
    }

    private void bounds(JsonNode record, Instant start, Instant end) {
        assertThat(record.get("fullStartedAt")).as("fullStartedAt").isNotNull();
        assertThat(record.get("fullEndedAt")).as("fullEndedAt").isNotNull();
        assertThat(record.get("fullStartedAt").asText()).isEqualTo(start.toString());
        assertThat(record.get("fullEndedAt").asText()).isEqualTo(end.toString());
    }

    @Test
    void bothPlannerDaysExposeTheSameFullRangeWhileKeepingDisplayClippingAndTotals() {
        seed("MEASURED", START, END, 5000, 3210);
        JsonNode previous = day(DAY.minusDays(1));
        JsonNode current = day(DAY);
        bounds(previous.at("/records/0"), START, END);
        bounds(current.at("/records/0"), START, END);
        assertThat(previous.at("/records/0/startedAt").asText()).isEqualTo(START.toString());
        assertThat(previous.at("/records/0/endedAt").asText()).isEqualTo("2026-10-03T20:00:00Z");
        assertThat(current.at("/records/0/startedAt").asText()).isEqualTo("2026-10-03T20:00:00Z");
        assertThat(current.at("/records/0/endedAt").asText()).isEqualTo(END.toString());
        assertThat(previous.get("focusSec").asLong() + current.get("focusSec").asLong())
                .isEqualTo(3210);
    }

    @ParameterizedTest
    @CsvSource({"MEASURED,true,5000,3210", "MEASURED,false,5000,3210", "MANUAL,true,5433,5433", "MANUAL,false,5433,5433"
    })
    void subjectOnlySavePreservesPreciseTimesAndDailySubjectPeriodAndRankingTotals(
            String source, boolean includeTimes, int study, int focus) {
        long id = seed(source, START, END, study, focus);
        JsonNode beforePrevious = day(DAY.minusDays(1));
        JsonNode beforeCurrent = day(DAY);
        long target = otherSubject();
        String key = (source.equals("MEASURED") ? "c:" : "m:") + START;
        JsonNode result = save(key, includeTimes ? START : null, includeTimes ? END : null, target);
        assertThat(result.get("success").asBoolean()).as(result.toString()).isTrue();
        for (LocalDate date : new LocalDate[] {DAY.minusDays(1), DAY}) {
            JsonNode actual = day(date);
            JsonNode before = date.equals(DAY) ? beforeCurrent : beforePrevious;
            assertThat(actual.get("studySec")).isEqualTo(before.get("studySec"));
            assertThat(actual.get("focusSec")).isEqualTo(before.get("focusSec"));
            assertThat(actual.at("/subjects/0/id").asLong()).isEqualTo(target);
            assertThat(actual.at("/subjects/0/focusSec")).isEqualTo(before.get("focusSec"));
        }
        JsonNode summary = call("GET", "/api/planner/summary?from=" + DAY.minusDays(1) + "&to=" + DAY, null, 200);
        assertThat(summary.get(0).get("focusSec").asLong()
                        + summary.get(1).get("focusSec").asLong())
                .isEqualTo(focus);
        assertThat(jdbc.queryForObject(
                        "SELECT started_at FROM planner_record_edit WHERE user_id=?", Timestamp.class, user))
                .isEqualTo(Timestamp.from(START));
        assertThat(jdbc.queryForObject(
                        "SELECT ended_at FROM planner_record_edit WHERE user_id=?", Timestamp.class, user))
                .isEqualTo(Timestamp.from(END));
        assertThat(jdbc.queryForObject("SELECT focus_sec FROM study_session WHERE id=?", Integer.class, id))
                .isEqualTo(focus);
        assertThat(jdbc.queryForObject("SELECT started_at FROM study_session WHERE id=?", Timestamp.class, id))
                .isEqualTo(Timestamp.from(START));
        if (source.equals("MEASURED"))
            assertThat(rankings.periodTotals(DAY, DAY, user).getFirst().focusSec())
                    .isEqualTo(focus);
        else assertThat(rankings.periodTotals(DAY, DAY, user)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEASURED", "MANUAL"})
    void editedRangeRemainsCompleteAndChangingOneEdgePreservesTheOtherPreciseEdge(String source) {
        seed(source, START, END, 5000, 3210);
        String key = (source.equals("MEASURED") ? "c:" : "m:") + START;
        Instant changedStart = Instant.parse("2026-10-03T19:40:29Z");
        JsonNode result = save(key, changedStart, END, subject);
        assertThat(result.get("success").asBoolean()).isTrue();
        Instant normalizedStart = Instant.parse("2026-10-03T19:40:00Z");
        bounds(day(DAY).at("/records/0"), normalizedStart, END);
        bounds(day(DAY.minusDays(1)).at("/records/0"), normalizedStart, END);
        long combined = day(DAY).get("focusSec").asLong()
                + day(DAY.minusDays(1)).get("focusSec").asLong();
        assertThat(combined).isEqualTo(4845);
    }

    @Test
    void midnightPiecesExposeBundleBoundsRatherThanOnlyOneDatabasePiece() {
        Instant start = Instant.parse("2026-10-03T14:30:12Z");
        Instant midnight = Instant.parse("2026-10-03T15:00:00Z");
        Instant end = Instant.parse("2026-10-03T16:00:00Z");
        seed("MEASURED", start, midnight, 1788, 1000);
        long second = seed("MEASURED", midnight, end, 3600, 2000);
        jdbc.update("UPDATE study_session SET submission_started_at=? WHERE id=?", Timestamp.from(start), second);
        bounds(day(DAY.minusDays(1)).at("/records/0"), start, end);
        JsonNode result = save("c:" + start, null, null, otherSubject());
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(day(DAY.minusDays(1)).get("focusSec").asLong()).isEqualTo(3000);
    }

    @Test
    void retryingSubjectOnlySaveReturnsTheSameResultAndKeepsBothDaysUnchanged() {
        seed("MEASURED", START, END, 5000, 3210);
        String body = "{\"operations\":[{\"operationId\":\"subject-retry\",\"action\":\"UPDATE\",\"key\":\"c:" + START
                + "\",\"subjectId\":" + otherSubject() + "}]}";
        var first = call("POST", "/api/planner/records/batch", body, 200);
        assertThat(first.at("/results/0/success").asBoolean()).isTrue();
        var previous = day(DAY.minusDays(1));
        var current = day(DAY);
        assertThat(call("POST", "/api/planner/records/batch", body, 200)).isEqualTo(first);
        assertThat(day(DAY.minusDays(1))).isEqualTo(previous);
        assertThat(day(DAY)).isEqualTo(current);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, user))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void subjectOnlyChangeOfGappedManualBundleDoesNotIntroduceAnOverlap(boolean includeTimes, boolean hadSubject) {
        Instant gapStart = Instant.parse("2026-10-03T19:45:00Z");
        Instant gapEnd = Instant.parse("2026-10-03T20:30:00Z");
        seed("MANUAL", START, gapStart, 887, 887);
        long second = seed("MANUAL", gapEnd, END, 1845, 1845);
        jdbc.update("UPDATE study_session SET submission_started_at=? WHERE id=?", Timestamp.from(START), second);
        seed("MEASURED", gapStart, gapEnd, 2700, 2000);
        if (!hadSubject) {
            jdbc.update(
                    "DELETE FROM study_session_subject_segment WHERE session_id IN (SELECT id FROM study_session WHERE user_id=? AND source='MANUAL')",
                    user);
        }
        var previous = day(DAY.minusDays(1));
        var current = day(DAY);
        String key = "m:" + START;
        long target = otherSubject();
        var result = save(key, includeTimes ? START : null, includeTimes ? END : null, target);
        assertThat(result.get("success").asBoolean()).as(result.toString()).isTrue();
        for (LocalDate date : new LocalDate[] {DAY.minusDays(1), DAY}) {
            var before = date.equals(DAY) ? current : previous;
            var after = day(date);
            assertThat(after.get("focusSec")).isEqualTo(before.get("focusSec"));
            assertThat(after.get("studySec")).isEqualTo(before.get("studySec"));
            assertThat(after.get("records"))
                    .filteredOn(record -> record.get("key").asText().equals(key))
                    .singleElement()
                    .satisfies(record -> assertThat(record.get("subjectSegments"))
                            .singleElement()
                            .satisfies(segment -> {
                                assertThat(segment.get("startedAt").asText())
                                        .isEqualTo((date.equals(DAY) ? gapEnd : START).toString());
                                assertThat(segment.get("endedAt").asText())
                                        .isEqualTo((date.equals(DAY) ? END : gapStart).toString());
                                assertThat(segment.get("subjectId").asLong()).isEqualTo(target);
                            }));
        }
        // 실제 구간을 변경하면 기존 충돌 검증은 계속 적용한다.
        assertThat(save(key, START.plusSeconds(60), END, target).get("status").asInt())
                .isEqualTo(409);
        assertThat(day(DAY).get("focusSec")).isEqualTo(current.get("focusSec"));
        var measured = save("c:" + gapStart, gapStart, gapEnd.minusSeconds(900), subject);
        assertThat(measured.get("success").asBoolean()).as(measured.toString()).isTrue();
    }

    @Test
    void legacyManualRecordKeepsItsIdKeyWhenOnlySubjectChanges() {
        long id = seed("MANUAL", START, END, 5433, 5433);
        jdbc.update("UPDATE study_session SET submission_started_at=null WHERE id=?", id);
        String key = "m:id:" + id;
        var result = save(key, null, null, otherSubject());
        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("key").asText()).isEqualTo(key);
        bounds(day(DAY).at("/records/0"), START, END);
        assertThat(day(DAY).at("/records/0/key").asText()).isEqualTo(key);
        assertThat(day(DAY).get("focusSec").asLong()
                        + day(DAY.minusDays(1)).get("focusSec").asLong())
                .isEqualTo(5433);
    }

    @Test
    void aSingleMissingEdgeIsRejectedWithoutSavingAnyOverride() {
        seed("MEASURED", START, END, 5000, 3210);
        JsonNode result = save("c:" + START, START, null, otherSubject());
        assertThat(result.get("status").asInt()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planner_record_edit WHERE user_id=?", Integer.class, user))
                .isZero();
    }
}
