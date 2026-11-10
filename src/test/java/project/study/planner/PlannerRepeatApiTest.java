package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PlannerRepeatApiTest extends PlannerApiSupport {
    @Test
    void numericAliasCannotReviveAnOccurrenceRemovedByRuleChange() {
        String root = daily(null);
        String old = keyOn(today.plusDays(2));
        long id = call("POST", "/api/planner/tasks/" + old + "/materialize", null, 200)
                .get("taskId")
                .asLong();
        call(
                "PUT",
                "/api/planner/tasks/" + root + "/repeat",
                "{\"weekdays\":[\"" + today.plusDays(1).getDayOfWeek() + "\"]}",
                200);
        call("PUT", "/api/planner/tasks/t:" + id + "/date", "{\"date\":\"" + today.plusDays(15) + "\"}", 404);
        call("PATCH", "/api/subjects/" + subject + "/tasks/" + id, "{\"name\":\"부활\"}", 404);
    }

    private String repeat(LocalDate date, String days, LocalDate end) {
        return call(
                        "POST",
                        "/api/planner/tasks",
                        "{\"subjectId\":" + subject + ",\"date\":\"" + date
                                + "\",\"name\":\"매일 공부\",\"repeat\":{\"weekdays\":" + days + ",\"endDate\":"
                                + (end == null ? "null" : "\"" + end + "\"") + "}}",
                        201)
                .get("key")
                .asText();
    }

    private String daily(LocalDate end) {
        return repeat(
                today, "[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]", end);
    }

    private JsonNode tasks(LocalDate date) {
        return call("GET", "/api/planner/tasks?from=" + date + "&to=" + date, null, 200);
    }

    private String keyOn(LocalDate date) {
        return tasks(date).get(0).get("key").asText();
    }

    @Test
    void completionsAndRemovalAffectOnlyOneOccurrenceAndMaterializationIsStable() {
        String key = daily(null);
        call("PATCH", "/api/planner/tasks/" + key, "{\"done\":true}", 200);
        assertThat(tasks(today).get(0).get("done").asBoolean()).isTrue();
        assertThat(tasks(today.plusDays(1)).get(0).get("done").asBoolean()).isFalse();
        String tomorrow = keyOn(today.plusDays(1));
        long id = call("POST", "/api/planner/tasks/" + tomorrow + "/materialize", null, 200)
                .get("taskId")
                .asLong();
        assertThat(call("POST", "/api/planner/tasks/" + tomorrow + "/materialize", null, 200)
                        .get("taskId")
                        .asLong())
                .isEqualTo(id);
        call("DELETE", "/api/planner/days/" + today.plusDays(1) + "/subjects/" + subject, null, 204);
        assertThat(tasks(today.plusDays(1))).isEmpty();
        assertThat(tasks(today.plusDays(2))).hasSize(1);
    }

    @Test
    void futureRuleChangesPreserveEarlierOccurrencesAndCustomizedFutureExceptions() {
        daily(null);
        String renamed = keyOn(today.plusDays(4));
        call("PATCH", "/api/planner/tasks/" + renamed, "{\"name\":\"특별\",\"scope\":\"THIS\"}", 200);
        String moved = keyOn(today.plusDays(5));
        assertThat(call(
                                "PUT",
                                "/api/planner/tasks/" + moved + "/date",
                                "{\"date\":\"" + today.plusDays(20) + "\"}",
                                409)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_DUPLICATE_OCCURRENCE");
        call("DELETE", "/api/planner/tasks/" + keyOn(today.plusDays(20)), null, 204);
        call("PUT", "/api/planner/tasks/" + moved + "/date", "{\"date\":\"" + today.plusDays(20) + "\"}", 200);
        String from = keyOn(today.plusDays(2));
        call(
                "PUT",
                "/api/planner/tasks/" + from + "/repeat",
                "{\"weekdays\":[\"" + today.plusDays(3).getDayOfWeek() + "\"]}",
                200);
        assertThat(tasks(today.plusDays(1))).hasSize(1);
        assertThat(tasks(today.plusDays(2))).hasSize(1);
        assertThat(tasks(today.plusDays(4)).get(0).get("name").asText()).isEqualTo("특별");
        assertThat(tasks(today.plusDays(20)).get(0).get("key").asText()).isEqualTo(moved);
        assertThat(tasks(today.plusDays(5))).isEmpty();
    }

    @Test
    void nameForwardKeepsEarlierNamesAndStoppingKeepsCurrentOrdinaryTask() {
        daily(null);
        String from = keyOn(today.plusDays(2));
        call("PATCH", "/api/planner/tasks/" + from, "{\"name\":\"새 이름\",\"scope\":\"FUTURE\"}", 200);
        assertThat(tasks(today).get(0).get("name").asText()).isEqualTo("매일 공부");
        assertThat(tasks(today.plusDays(3)).get(0).get("name").asText()).isEqualTo("새 이름");
        var stopped = call("PUT", "/api/planner/tasks/" + from + "/repeat", "{\"weekdays\":[]}", 200);
        assertThat(stopped.get("seriesId").isNull()).isTrue();
        assertThat(tasks(today.plusDays(2))).hasSize(1);
        assertThat(tasks(today.plusDays(3))).isEmpty();
    }

    @Test
    void initialTaskSurvivesUnselectedWeekdayAndInfiniteCapacityChecksDistantDates() {
        repeat(today, "[\"" + today.plusDays(1).getDayOfWeek() + "\"]", null);
        assertThat(tasks(today)).hasSize(1);
        assertThat(tasks(today.plusDays(1))).hasSize(1);
        long other = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?, '영어') RETURNING id", Long.class, user);
        subject = other;
        for (int i = 0; i < 30; i++) add(today.plusDays(1000), "미래" + i);
        assertThat(call(
                                "POST",
                                "/api/planner/tasks",
                                "{\"subjectId\":" + subject + ",\"date\":\"" + today
                                        + "\",\"name\":\"반복 충돌\",\"repeat\":{\"weekdays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}}",
                                409)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_TASK_LIMIT");
        assertThat(tasks(today)).hasSize(1);
        assertThat(tasks(today.plusDays(1000)).size()).isGreaterThanOrEqualTo(30);
    }
}
