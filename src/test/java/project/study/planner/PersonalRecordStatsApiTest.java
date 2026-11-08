package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PersonalRecordStatsApiTest extends PlannerApiSupport {
    @Test
    void emptyPersonalDayHasNoInventedSessionsAndZeroTotals() {
        var response = call("GET", "/api/planner/records/stats?date=2026-10-01&timeZone=Asia/Seoul", null, 200);
        assertThat(response.get("sessions").size()).isZero();
        assertThat(response.get("totalStudySec").asLong()).isZero();
        assertThat(response.get("totalFocusSec").asLong()).isZero();
    }

    @Test
    void unchangedOriginalHasItsRealIdPreciseSecondsAndNoEditedMark() {
        long id = session("2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z", 3600, 3600, "Asia/Seoul");
        var response = call("GET", "/api/planner/records/stats?date=2026-10-01&timeZone=Asia/Seoul", null, 200);
        assertThat(response.get("totalStudySec").asLong()).isEqualTo(3600);
        var record = response.get("sessions").get(0);
        assertThat(record.get("originalSessionId").asLong()).isEqualTo(id);
        assertThat(record.get("edited").asBoolean()).isFalse();
        assertThat(record.get("displayKey").asText()).isNotBlank();
        assertThat(record.has("id")).isFalse();
        var planner = call("GET", "/api/planner/days/2026-10-01?timeZone=Asia/Seoul", null, 200);
        assertThat(planner.get("records").get(0).get("edited").asBoolean()).isFalse();
    }

    private long session(String start, String end, int study, int focus, String zone) {
        return jdbc.queryForObject(
                """
                INSERT INTO study_session(user_id,stat_date,started_at,ended_at,submission_started_at,study_sec,focus_sec,time_zone)
                VALUES (?,?::date,?,?,?,?,?,?) RETURNING id
                """,
                Long.class,
                user,
                Instant.parse(start)
                        .atZone(java.time.ZoneId.of(zone))
                        .toLocalDate()
                        .toString(),
                Timestamp.from(Instant.parse(start)),
                Timestamp.from(Instant.parse(end)),
                Timestamp.from(Instant.parse(start)),
                study,
                focus,
                zone);
    }

    @Test
    void editedSubjectlessRecordSurvivesReloadWithPersonalSecondsButRawSecondsStayUntouched() {
        long id = session("2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z", 3600, 3600, "Asia/Seoul");
        var result = call("POST", "/api/planner/records/batch", """
                {"operations":[{"operationId":"change-subjectless","action":"UPDATE",
                "key":"c:2026-10-01T09:00:00Z","startedAt":"2026-10-01T09:10:00Z",
                "endedAt":"2026-10-01T10:20:00Z","subjectId":null}]}
                """, 200);
        assertThat(result.get("results").get(0).get("success").asBoolean()).isTrue();
        var response = call("GET", "/api/planner/records/stats?date=2026-10-01&timeZone=Asia/Seoul", null, 200);
        assertThat(response.get("totalStudySec").asLong()).isEqualTo(4200);
        var record = response.get("sessions").get(0);
        assertThat(record.get("edited").asBoolean()).isTrue();
        assertThat(record.get("subjectSegments").size()).isZero();
        assertThat(record.get("source").asText()).isEqualTo("MEASURED");
        assertThat(record.get("submissionStartedAt").asText()).isEqualTo("2026-10-01T09:00:00Z");
        assertThat(jdbc.queryForObject("SELECT study_sec FROM study_session WHERE id=?", Integer.class, id))
                .isEqualTo(3600);
        assertThat(call("GET", "/api/planner/days/2026-10-01", null, 200)
                        .get("records")
                        .get(0)
                        .get("edited")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void periodIncludesPersonalManualWithoutInventedIdAndFillsEmptyComparisonDays() {
        call("POST", "/api/planner/records/batch", """
                {"operations":[{"operationId":"new-personal","action":"CREATE",
                "startedAt":"2026-10-03T09:00:00Z","endedAt":"2026-10-03T10:00:00Z","subjectId":null}]}
                """, 200);
        var period = call(
                "GET",
                "/api/planner/records/stats/period?from=2026-10-02&to=2026-10-04"
                        + "&compareFrom=2026-09-29&compareTo=2026-10-01&timeZone=Asia/Seoul",
                null,
                200);
        assertThat(period.get("dailyList").size()).isEqualTo(3);
        assertThat(period.get("dailyList").get(0).get("focusSec").asLong()).isZero();
        assertThat(period.get("dailyList").get(1).get("focusSec").asLong()).isEqualTo(3600);
        assertThat(period.get("compareDailyList").size()).isEqualTo(3);
        var day = call("GET", "/api/planner/records/stats?date=2026-10-03&timeZone=Asia/Seoul", null, 200);
        assertThat(day.get("sessions").get(0).get("originalSessionId").isNull()).isTrue();
        assertThat(day.get("sessions").get(0).get("edited").asBoolean()).isTrue();
        assertThat(day.get("focusRate").asDouble()).isZero();
        assertThat(day.get("longestFocusSec").asLong()).isZero();
    }

    @Test
    void rhythmUsesMovedMeasuredPersonalRecordExcludesDeletedAndManualAndKeepsOriginals() {
        session("2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z", 3600, 3600, "Asia/Seoul");
        session("2026-10-04T09:00:00Z", "2026-10-04T09:30:00Z", 1800, 1800, "Asia/Seoul");
        call("POST", "/api/planner/records/batch", """
                {"operations":[
                {"operationId":"move","action":"UPDATE","key":"c:2026-10-01T09:00:00Z",
                "startedAt":"2026-10-03T09:00:00Z","endedAt":"2026-10-03T10:00:00Z","subjectId":null},
                {"operationId":"delete","action":"DELETE","key":"c:2026-10-04T09:00:00Z"},
                {"operationId":"manual","action":"CREATE","startedAt":"2026-10-02T09:00:00Z",
                "endedAt":"2026-10-02T10:00:00Z","subjectId":null}]}
                """, 200);
        var rhythm = call("GET", "/api/planner/records/stats/rhythm?timeZone=Asia/Seoul", null, 200);
        assertThat(rhythm.get("studyDays").asLong()).isEqualTo(1);
        assertThat(rhythm.get("focusSecByWeekdayHour").get(5).get(18).asInt()).isEqualTo(3600);
        long sum = 0;
        for (var row : rhythm.get("focusSecByWeekdayHour")) for (var cell : row) sum += cell.asLong();
        assertThat(sum).isEqualTo(3600);
        assertThat(jdbc.queryForObject("SELECT sum(focus_sec) FROM study_session WHERE user_id=?", Long.class, user))
                .isEqualTo(5400);
    }

    @Test
    void legacyMissingEndStillCountsAsStudyDayButDoesNotInventRhythmSlots() {
        long id = session("2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z", 3600, 3600, "Asia/Seoul");
        jdbc.update("UPDATE study_session SET ended_at=null WHERE id=?", id);
        var rhythm = call("GET", "/api/planner/records/stats/rhythm?timeZone=Asia/Seoul", null, 200);
        assertThat(rhythm.get("studyDays").asLong()).isEqualTo(1);
        assertThat(rhythm.get("focusSecByWeekdayHour").get(3).get(18).asInt()).isZero();
    }
}
