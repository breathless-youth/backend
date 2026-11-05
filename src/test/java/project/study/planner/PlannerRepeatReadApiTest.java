package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PlannerRepeatReadApiTest extends PlannerApiSupport {
    private LocalDate monday() {
        return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private String repeating(LocalDate start, LocalDate end) {
        String key = add(start, "반복 편집");
        return call("PUT", "/api/planner/tasks/" + key + "/repeat", config(end), 200)
                .get("key")
                .asText();
    }

    private String config(LocalDate end) {
        return "{\"weekdays\":[\"MONDAY\",\"WEDNESDAY\",\"FRIDAY\"],\"endDate\":"
                + (end == null ? "null" : "\"" + end + "\"") + "}";
    }

    private JsonNode settings(String key) {
        return call("GET", "/api/planner/tasks/" + key + "/repeat", null, 200);
    }

    @Test
    void readsWeekdaysAndEndDateAndRoundTripsWithoutChangingOccurrences() {
        LocalDate start = monday();
        LocalDate end = start.plusDays(30);
        String key = repeating(start, end);
        var before = day(start);
        var repeat = settings(key);
        assertThat(repeat.get("weekdays"))
                .extracting(JsonNode::asText)
                .containsExactly("MONDAY", "WEDNESDAY", "FRIDAY");
        assertThat(repeat.get("startDate").asText()).isEqualTo(start.toString());
        assertThat(repeat.get("endDate").asText()).isEqualTo(end.toString());
        assertThat(repeat.get("seriesId").asLong()).isPositive();
        call("PUT", "/api/planner/tasks/" + key + "/repeat", config(end), 200);
        assertThat(settings(key)).isEqualTo(repeat);
        assertThat(day(start)).isEqualTo(before);
        assertThat(settings("t:" + before.at("/subjects/0/tasks/0/taskId").asLong()))
                .isEqualTo(repeat);
    }

    @Test
    void readsUnboundedRulesAndOriginalDayOutsideSelectedWeekdays() {
        String key = add(monday().plusDays(1), "화요일에 만든 반복");
        key = call("PUT", "/api/planner/tasks/" + key + "/repeat", config(null), 200)
                .get("key")
                .asText();
        var repeat = settings(key);
        assertThat(repeat.get("startDate").asText())
                .isEqualTo(monday().plusDays(1).toString());
        assertThat(repeat.get("weekdays")).hasSize(3);
        assertThat(repeat.get("endDate").isNull()).isTrue();
    }

    @Test
    void movedOccurrenceReadsRuleVersionAtItsCurrentPlanDate() {
        LocalDate start = monday();
        String key = repeating(start, start.plusDays(30));
        long series = settings(key).get("seriesId").asLong();
        LocalDate boundary = start.plusDays(2);
        call(
                "PUT",
                "/api/planner/tasks/r:" + series + ":" + boundary + "/repeat",
                "{\"weekdays\":[\"TUESDAY\",\"THURSDAY\"],\"endDate\":\"" + start.plusDays(20) + "\"}",
                200);
        assertThat(settings(key).get("endDate").asText())
                .isEqualTo(start.plusDays(1).toString());
        call("PUT", "/api/planner/tasks/" + key + "/date", "{\"date\":\"" + start.plusDays(4) + "\"}", 200);
        var moved = settings(key);
        assertThat(moved.get("startDate").asText()).isEqualTo(boundary.toString());
        assertThat(moved.get("weekdays")).extracting(JsonNode::asText).containsExactly("TUESDAY", "THURSDAY");
        assertThat(moved.get("endDate").asText()).isEqualTo(start.plusDays(20).toString());
    }

    @Test
    void ordinaryTasksAndPreservedExceptionsOfStoppedRulesReadAsNoRepeat() {
        var plain = settings(add(today, "보통 할 일"));
        assertThat(plain.get("seriesId").isNull()).isTrue();
        assertThat(plain.get("weekdays")).isEmpty();
        String key = repeating(monday(), null);
        long series = settings(key).get("seriesId").asLong();
        String exception = "r:" + series + ":" + monday().plusDays(7);
        call("PATCH", "/api/planner/tasks/" + exception, "{\"name\":\"개별 예외\"}", 200);
        call("PUT", "/api/planner/tasks/" + key + "/repeat", "{\"weekdays\":[]}", 200);
        var stopped = settings(exception);
        assertThat(stopped.get("seriesId").asLong()).isEqualTo(series);
        assertThat(stopped.get("weekdays")).isEmpty();
        assertThat(stopped.get("startDate").isNull()).isTrue();
        assertThat(stopped.get("endDate").isNull()).isTrue();
    }

    @Test
    void settingsDoNotMaterializeFutureOccurrencesAndHonorOwnershipAndDeletion() {
        String key = repeating(monday(), null);
        long series = settings(key).get("seriesId").asLong();
        String virtual = "r:" + series + ":" + monday().plusDays(7);
        long count = jdbc.queryForObject("SELECT count(*) FROM study_task WHERE series_id=?", Long.class, series);
        settings(virtual);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM study_task WHERE series_id=?", Long.class, series))
                .isEqualTo(count);
        long owner = user;
        user = newUser();
        call("GET", "/api/planner/tasks/" + virtual + "/repeat", null, 404);
        user = owner;
        call("DELETE", "/api/planner/tasks/" + virtual, null, 204);
        call("GET", "/api/planner/tasks/" + virtual + "/repeat", null, 404);
        call("GET", "/api/planner/tasks/not-a-key/repeat", null, 400);
        assertThat(mvc.get().uri("/api/planner/tasks/" + key + "/repeat").header("API-Version", "1"))
                .hasStatus(401);
    }
}
