package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PersonalRecordStatsContractApiTest extends PlannerApiSupport {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/planner/records/stats?date=0000-01-01",
                "/api/planner/records/stats/period?from=2026-10-02&to=2026-10-01",
                "/api/planner/records/stats/period?from=2024-01-01&to=2025-01-01",
                "/api/planner/records/stats/period?from=2026-10-01&to=2026-10-02&compareFrom=2026-09-01",
                "/api/planner/records/stats/period?from=2026-10-01&to=2026-10-02&compareFrom=2026-09-03&compareTo=2026-09-01",
                "/api/planner/records/stats/period?from=2026-10-01&to=2026-10-02&compareFrom=2024-01-01&compareTo=2025-01-01"
            })
    void rejectsInvalidDatesAndIncompleteOrOverlongRanges(String uri) {
        call("GET", uri, null, 400);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/planner/records/stats?date=2026-10-01",
                "/api/planner/records/stats/period?from=2026-10-01&to=2026-10-02",
                "/api/planner/records/stats/rhythm"
            })
    void personalStatsRequireAuthentication(String uri) {
        assertThat(mvc.get()
                        .uri(uri)
                        .header("API-Version", "1")
                        .exchange()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void readingInAnotherTimeZoneDoesNotSaveThatZoneOrAcceptAnotherUsersId() {
        String before = jdbc.queryForObject("SELECT time_zone FROM users WHERE id=?", String.class, user);
        long other = newUser();
        jdbc.update("""
                INSERT INTO study_session(user_id,stat_date,started_at,ended_at,study_sec,focus_sec)
                VALUES (?,'2026-10-01',TIMESTAMPTZ '2026-10-01 09:00:00Z',TIMESTAMPTZ '2026-10-01 10:00:00Z',3600,3600)
                """, other);
        var response = call(
                "GET",
                "/api/planner/records/stats?date=2026-10-01&timeZone=America/New_York&userId=" + other,
                null,
                200);
        assertThat(response.get("sessions").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT time_zone FROM users WHERE id=?", String.class, user))
                .isEqualTo(before);
        assertThat(call("GET", "/api/planner/records/stats/rhythm?userId=" + other, null, 200)
                        .get("studyDays")
                        .asLong())
                .isZero();
    }

    @Test
    void generatedSwaggerDefinesRealDisplayIdentityEditedFlagAndAllThreeContracts() {
        var api = call("GET", "/v3/api-docs", null, 200);
        for (String path : List.of(
                "/api/planner/records/stats",
                "/api/planner/records/stats/period",
                "/api/planner/records/stats/rhythm")) {
            var operation = api.get("paths").get(path).get("get");
            assertThat(operation.get("responses").has("200")).isTrue();
            assertThat(operation.get("responses").has("401")).isTrue();
        }
        var schema = api.at("/components/schemas/PersonalRecordSummaryResponse/properties");
        assertThat(schema.propertyNames())
                .contains(
                        "displayKey",
                        "recordKey",
                        "originalSessionId",
                        "edited",
                        "events",
                        "subjectSegments",
                        "completedTasks",
                        "fullStartedAt",
                        "fullEndedAt");
        assertThat(schema.has("id")).isFalse();
        assertThat(api.at("/components/schemas/PlannerRecordView/properties/edited/type")
                        .asText())
                .isEqualTo("boolean");
    }
}
