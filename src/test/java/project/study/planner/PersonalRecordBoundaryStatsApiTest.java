package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

class PersonalRecordBoundaryStatsApiTest extends PlannerApiSupport {
    @Test
    void subjectOnlyEditKeepsStoredCalendarDateAndMicrosecondsAfterChangingDeviceZone() {
        String start = "2026-10-01T23:30:12.123456Z";
        session(start, "2026-10-01T23:32:12.123456Z", 120, 100, "Asia/Seoul");
        update("c:" + start, null, null, subject);
        JsonNode day = personal("2026-10-02", "America/Los_Angeles");
        assertThat(day.get("totalStudySec").asLong()).isEqualTo(120);
        assertThat(day.get("totalFocusSec").asLong()).isEqualTo(100);
        JsonNode row = day.get("sessions").get(0);
        assertThat(row.get("statDate").asText()).isEqualTo("2026-10-02");
        assertThat(row.get("startedAt").asText()).isEqualTo(start);
        assertThat(row.get("edited").asBoolean()).isTrue();
        assertThat(row.get("subjectSegments").get(0).get("subjectId").asLong()).isEqualTo(subject);
    }

    @Test
    void changedMeasuredTimesSplitAtOriginalZoneMidnightRatherThanFiveOrDeviceZone() {
        String start = "2026-10-01T14:30:00Z";
        session(start, "2026-10-01T14:50:00Z", 1200, 1200, "Asia/Seoul");
        update("c:" + start, start, "2026-10-01T15:30:00Z", null);
        assertThat(personal("2026-10-01", "America/New_York")
                        .get("totalFocusSec")
                        .asLong())
                .isEqualTo(1800);
        assertThat(personal("2026-10-02", "America/New_York")
                        .get("totalFocusSec")
                        .asLong())
                .isEqualTo(1800);
        var original = mvc.get()
                .uri("/api/stats?date=2026-10-01")
                .header("API-Version", "2")
                .with(asUser(user))
                .exchange();
        assertThat(original.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(original.getResponse().getContentAsByteArray())
                        .get("totalFocusSec")
                        .asLong())
                .isEqualTo(1200);
    }

    @Test
    void subjectlessGappedManualKeepsSeparatePiecesAndDoesNotFillOtherRecordsGap() {
        String first = "2026-10-01T09:00:00Z";
        long a = session(first, "2026-10-01T09:10:00Z", 600, 600, "Asia/Seoul");
        long b = session("2026-10-01T09:20:00Z", "2026-10-01T09:30:00Z", 600, 600, "Asia/Seoul");
        session("2026-10-01T09:10:00Z", "2026-10-01T09:20:00Z", 600, 600, "Asia/Seoul");
        jdbc.update(
                "UPDATE study_session SET source='MANUAL',submission_started_at=? WHERE id in (?,?)",
                Timestamp.from(Instant.parse(first)),
                a,
                b);
        update("m:" + first, null, null, null);
        JsonNode day = personal("2026-10-01", "Asia/Seoul");
        assertThat(day.get("totalFocusSec").asLong()).isEqualTo(1800);
        assertThat(day.get("sessionCount").asInt()).isEqualTo(3);
        int edited = 0;
        for (JsonNode row : day.get("sessions")) {
            if (row.get("edited").asBoolean()) {
                edited++;
                assertThat(row.get("studySec").asLong()).isEqualTo(600);
                assertThat(row.get("subjectSegments").size()).isZero();
            }
        }
        assertThat(edited).isEqualTo(2);
        JsonNode rhythm = call("GET", "/api/planner/records/stats/rhythm", null, 200);
        assertThat(rhythm.get("focusSecByWeekdayHour").get(3).get(18).asLong()).isEqualTo(600);
    }

    @Test
    void changedRangeClipsEventsForCountsFocusLongestAndRhythmWithoutMovingEvents() {
        String start = "2026-10-01T09:00:00Z";
        long id = session(start, "2026-10-01T10:00:00Z", 3000, 2400, "Asia/Seoul");
        event(id, "PAUSE", "2026-10-01T09:20:00Z", "2026-10-01T09:30:00Z");
        event(id, "PHONE", "2026-10-01T09:40:00Z", "2026-10-01T09:50:00Z");
        update("c:" + start, "2026-10-01T09:25:00Z", "2026-10-01T09:55:00Z", null);
        JsonNode day = personal("2026-10-01", "Asia/Seoul");
        assertThat(day.get("totalStudySec").asLong()).isEqualTo(1500);
        assertThat(day.get("totalFocusSec").asLong()).isEqualTo(900);
        assertThat(day.get("longestFocusSec").asLong()).isEqualTo(600);
        assertThat(day.get("focusRate").asDouble()).isEqualTo(60);
        assertThat(day.get("totalEventCounts").get("PAUSE").asLong()).isEqualTo(1);
        assertThat(day.get("totalEventCounts").get("PHONE").asLong()).isEqualTo(1);
        JsonNode events = day.get("sessions").get(0).get("events");
        assertThat(events.get(0).get("startedAt").asText()).isEqualTo("2026-10-01T09:25:00Z");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM status_event WHERE session_id=?", Long.class, id))
                .isEqualTo(2);
        assertThat(call("GET", "/api/planner/records/stats/rhythm", null, 200)
                        .get("focusSecByWeekdayHour")
                        .get(3)
                        .get(18)
                        .asLong())
                .isEqualTo(900);
    }

    @Test
    void fourSecondVisualCellDoesNotCreateSixtySecondsInStatsOrRhythm() {
        session("2026-10-01T09:29:49Z", "2026-10-01T09:29:53Z", 4, 4, "Asia/Seoul");
        JsonNode day = personal("2026-10-01", "Asia/Seoul");
        assertThat(day.get("sessionCount").asInt()).isEqualTo(1);
        assertThat(day.get("totalStudySec").asLong()).isEqualTo(4);
        assertThat(day.get("totalFocusSec").asLong()).isEqualTo(4);
        assertThat(call("GET", "/api/planner/days/2026-10-01", null, 200)
                        .get("records")
                        .size())
                .isEqualTo(1);
        JsonNode rhythm = call("GET", "/api/planner/records/stats/rhythm", null, 200);
        assertThat(rhythm.get("studyDays").asLong()).isEqualTo(1);
        assertThat(rhythm.get("focusSecByWeekdayHour").get(3).get(18).asLong()).isEqualTo(4);
    }

    @Test
    void personalManualUsesRequestedZoneBecauseItsOriginalInputZoneWasNotStored() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", "manual-zone");
        op.put("action", "CREATE");
        op.put("startedAt", "2026-10-01T23:30:00Z");
        op.put("endedAt", "2026-10-02T00:30:00Z");
        call("POST", "/api/planner/records/batch", json.writeValueAsString(Map.of("operations", List.of(op))), 200);
        assertThat(personal("2026-10-01", "UTC").get("totalFocusSec").asLong()).isEqualTo(1800);
        assertThat(personal("2026-10-02", "UTC").get("totalFocusSec").asLong()).isEqualTo(1800);
        assertThat(personal("2026-10-02", "Asia/Seoul").get("totalFocusSec").asLong())
                .isEqualTo(3600);
    }

    @Test
    void autumnDstKeepsBothOccurrencesOfOneOClockInTheSameRhythmBucket() {
        String start = "2026-11-01T05:00:00Z";
        session(start, "2026-11-01T05:30:00Z", 1800, 1800, "America/New_York");
        update("c:" + start, "2026-11-01T05:30:00Z", "2026-11-01T07:30:00Z", null);
        assertThat(personal("2026-11-01", "America/New_York")
                        .get("totalFocusSec")
                        .asLong())
                .isEqualTo(7200);
        JsonNode grid = call("GET", "/api/planner/records/stats/rhythm?timeZone=America/New_York", null, 200)
                .get("focusSecByWeekdayHour");
        assertThat(grid.get(6).get(1).asLong()).isEqualTo(5400);
        assertThat(grid.get(6).get(2).asLong()).isEqualTo(1800);
    }

    @Test
    void movedRecordReferencesCompletedTaskOnceAndRetainsDeletedSubjectMetadataAndRawCompletion() {
        String start = "2026-10-01T14:30:00Z";
        long id = session(start, "2026-10-01T14:50:00Z", 1200, 1200, "Asia/Seoul");
        long task = jdbc.queryForObject("""
                INSERT INTO study_task(subject_id,name,planned_date,done_at)
                VALUES (?,'원본 완료','2026-10-01',TIMESTAMPTZ '2026-10-01 14:40:00Z') RETURNING id
                """, Long.class, subject);
        jdbc.update("INSERT INTO study_session_task_done(session_id,task_id) VALUES (?,?)", id, task);
        jdbc.update("UPDATE study_subject SET deleted_at=TIMESTAMPTZ '2026-09-01 00:00:00Z' WHERE id=?", subject);
        update("c:" + start, start, "2026-10-01T15:30:00Z", null);
        JsonNode first = personal("2026-10-01", "Asia/Seoul");
        JsonNode next = personal("2026-10-02", "Asia/Seoul");
        assertThat(first.get("sessions")
                        .get(0)
                        .get("completedTasks")
                        .get(0)
                        .get("id")
                        .asLong())
                .isEqualTo(task);
        assertThat(next.get("sessions").get(0).get("completedTasks").size()).isZero();
        assertThat(first.get("subjects").size()).isEqualTo(1);
        assertThat(first.get("subjects").get(0).get("id").asLong()).isEqualTo(subject);
        assertThat(jdbc.queryForObject("SELECT planned_date::text FROM study_task WHERE id=?", String.class, task))
                .isEqualTo("2026-10-01");
        assertThat(jdbc.queryForObject("SELECT done_at FROM study_task WHERE id=?", Timestamp.class, task)
                        .toInstant())
                .isEqualTo(Instant.parse("2026-10-01T14:40:00Z"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM study_session_task_done WHERE session_id=? AND task_id=?",
                        Integer.class,
                        id,
                        task))
                .isEqualTo(1);
    }

    @Test
    void springDstDoesNotInventTheMissingTwoOClockHour() {
        String start = "2026-03-08T06:00:00Z";
        session(start, "2026-03-08T06:30:00Z", 1800, 1800, "America/New_York");
        update("c:" + start, "2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z", null);
        assertThat(personal("2026-03-08", "America/New_York")
                        .get("totalFocusSec")
                        .asLong())
                .isEqualTo(3600);
        JsonNode grid = call("GET", "/api/planner/records/stats/rhythm?timeZone=America/New_York", null, 200)
                .get("focusSecByWeekdayHour");
        assertThat(grid.get(6).get(1).asLong()).isEqualTo(1800);
        assertThat(grid.get(6).get(2).asLong()).isZero();
        assertThat(grid.get(6).get(3).asLong()).isEqualTo(1800);
    }

    @Test
    void legacyNullSubmissionHasAnActualFallbackAndNullableRootIsNeverSentToFrontend() {
        String start = "2026-10-01T09:00:00Z";
        long id = session(start, "2026-10-01T10:00:00Z", 3600, 3600, "Asia/Seoul");
        jdbc.update("UPDATE study_session SET submission_started_at=null WHERE id=?", id);
        update("c:id:" + id, "2026-10-01T09:10:00Z", "2026-10-01T10:00:00Z", null);
        JsonNode row = personal("2026-10-01", "Asia/Seoul").get("sessions").get(0);
        assertThat(row.get("submissionStartedAt").asText()).isEqualTo(start);
        assertThat(row.get("fullStartedAt").asText()).isEqualTo("2026-10-01T09:10:00Z");
        assertThat(row.get("originalSessionId").asLong()).isEqualTo(id);
    }

    @Test
    void renamedLegacyManualKeepsPersonalTimesAndTombstoneWithoutInventingLostOriginalLink() {
        String oldStart = "2026-09-30T14:30:00Z";
        String newStart = "2026-10-01T23:30:00Z";
        long id = session(oldStart, "2026-09-30T14:50:00Z", 1200, 1200, "Asia/Seoul");
        jdbc.update("UPDATE study_session SET source='MANUAL' WHERE id=?", id);
        update("m:" + oldStart, newStart, "2026-10-02T00:30:00Z", null);
        assertThat(jdbc.queryForObject(
                        "SELECT deleted FROM planner_record_edit WHERE user_id=? AND record_key=?",
                        Boolean.class,
                        user,
                        "m:" + oldStart))
                .isTrue();
        assertThat(personal("2026-09-30", "UTC").get("sessionCount").asInt()).isZero();
        JsonNode row = personal("2026-10-01", "UTC").get("sessions").get(0);
        assertThat(row.get("recordKey").asText()).isEqualTo("m:" + newStart);
        assertThat(row.get("edited").asBoolean()).isTrue();
        assertThat(row.get("originalSessionId").isNull()).isTrue();
        assertThat(row.get("completedTasks").size()).isZero();
        assertThat(row.get("studySec").asLong()).isEqualTo(1800);
        assertThat(personal("2026-10-02", "UTC").get("totalStudySec").asLong()).isEqualTo(1800);
        JsonNode period =
                call("GET", "/api/planner/records/stats/period?from=2026-09-30&to=2026-10-02&timeZone=UTC", null, 200);
        assertThat(period.get("dailyList").get(0).get("studySec").asLong()).isZero();
        assertThat(period.get("dailyList").get(1).get("studySec").asLong()).isEqualTo(1800);
        assertThat(period.get("dailyList").get(2).get("studySec").asLong()).isEqualTo(1800);
        var raw = mvc.get()
                .uri("/api/stats?date=2026-09-30")
                .header("API-Version", "2")
                .with(asUser(user))
                .exchange();
        assertThat(json.readTree(raw.getResponse().getContentAsByteArray())
                        .get("totalStudySec")
                        .asLong())
                .isEqualTo(1200);
        assertThat(jdbc.queryForObject("SELECT started_at FROM study_session WHERE id=?", Timestamp.class, id)
                        .toInstant())
                .isEqualTo(Instant.parse(oldStart));
    }

    private JsonNode personal(String date, String zone) {
        return call("GET", "/api/planner/records/stats?date=" + date + "&timeZone=" + zone, null, 200);
    }

    @ParameterizedTest
    @CsvSource({"14:59:44.190,15", "14:59:59,1", "14:59:59.500,0"})
    void completedTaskReferenceUsesFirstVisiblePieceAtOneSecondMidnightBoundary(String time, int prefixFocusSec) {
        String start = "2026-10-01T" + time + "Z";
        long id = session(start, Instant.parse(start).plusSeconds(5).toString(), 5, 5, "Asia/Seoul");
        long task = jdbc.queryForObject(
                """
                INSERT INTO study_task(subject_id,name,planned_date,done_at)
                VALUES (?,'짧은 원본 완료','2026-10-01',?) RETURNING id
                """, Long.class, subject, Timestamp.from(Instant.parse(start).plusSeconds(1)));
        jdbc.update("INSERT INTO study_session_task_done(session_id,task_id) VALUES (?,?)", id, task);
        update("c:" + start, start, "2026-10-01T15:30:00Z", null);
        JsonNode firstDay = personal("2026-10-01", "Asia/Seoul");
        assertThat(firstDay.get("sessionCount").asInt()).isEqualTo(prefixFocusSec > 0 ? 1 : 0);
        assertThat(firstDay.get("totalFocusSec").asLong()).isEqualTo(prefixFocusSec);
        if (prefixFocusSec > 0) {
            JsonNode tasks = firstDay.get("sessions").get(0).get("completedTasks");
            assertThat(tasks.size()).isEqualTo(1);
            assertThat(tasks.get(0).get("id").asLong()).isEqualTo(task);
        }
        JsonNode row = personal("2026-10-02", "Asia/Seoul").get("sessions").get(0);
        assertThat(row.get("completedTasks").size()).isEqualTo(prefixFocusSec > 0 ? 0 : 1);
        if (prefixFocusSec == 0) {
            assertThat(row.get("completedTasks").get(0).get("id").asLong()).isEqualTo(task);
        }
    }

    private long session(String start, String end, int study, int focus, String zone) {
        return jdbc.queryForObject(
                """
                INSERT INTO study_session(user_id,stat_date,started_at,ended_at,submission_started_at,study_sec,focus_sec,time_zone)
                VALUES (?,?::date,?,?,?,?,?,?) RETURNING id
                """,
                Long.class,
                user,
                Instant.parse(start).atZone(ZoneId.of(zone)).toLocalDate().toString(),
                Timestamp.from(Instant.parse(start)),
                Timestamp.from(Instant.parse(end)),
                Timestamp.from(Instant.parse(start)),
                study,
                focus,
                zone);
    }

    private void event(long id, String status, String start, String end) {
        jdbc.update(
                "INSERT INTO status_event(session_id,status,started_at,ended_at) VALUES (?,?,?,?)",
                id,
                status,
                Timestamp.from(Instant.parse(start)),
                Timestamp.from(Instant.parse(end)));
    }

    private void update(String key, String start, String end, Long selectedSubject) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", UUID.randomUUID().toString());
        op.put("action", "UPDATE");
        op.put("key", key);
        op.put("subjectId", selectedSubject);
        if (start != null) {
            op.put("startedAt", start);
            op.put("endedAt", end);
        }
        JsonNode result = call(
                "POST", "/api/planner/records/batch", json.writeValueAsString(Map.of("operations", List.of(op))), 200);
        assertThat(result.get("results").get(0).get("success").asBoolean()).isTrue();
    }
}
