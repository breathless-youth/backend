package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlannerRegressionApiTest extends PlannerApiSupport {
    @Test
    void restoringGlobalSubjectDoesNotReviveItsRepeatingFuturePlan() {
        series(today, 127);
        call("PUT", "/api/planner/days/" + today.plusDays(5) + "/subjects/" + subject, null, 204);
        call("DELETE", "/api/subjects/" + subject, null, 204);
        var restored = call("POST", "/api/subjects", "{\"name\":\"수학\"}", 201);
        assertThat(restored.get("id").asLong()).isEqualTo(subject);
        assertThat(day(today.plusDays(5)).get("subjects")).isEmpty();
    }

    private long series(LocalDate start, int weekdays) {
        long id = jdbc.queryForObject(
                "INSERT INTO planner_series(user_id,subject_id) VALUES (?,?) RETURNING id", Long.class, user, subject);
        jdbc.update(
                "INSERT INTO planner_repeat_rule(series_id,name,start_date,weekdays) VALUES (?,'반복',?,?)",
                id,
                start,
                weekdays);
        return id;
    }

    @Test
    void exceptionsCannotMaskInfiniteRecurrenceCapacityAfterFirstWeek() {
        long first = series(today, 127);
        for (int i = 1; i < 30; i++) series(today, 127);
        for (int i = 0; i < 7; i++) {
            LocalDate date = today.plusDays(i);
            jdbc.update(
                    "INSERT INTO study_task(subject_id,name,planned_date,series_id,occurrence_date,deleted_at) VALUES (?,'이 날만 삭제',?,?,?,now())",
                    subject,
                    date,
                    first,
                    date);
        }
        call(
                "POST",
                "/api/planner/tasks",
                "{\"subjectId\":" + subject + ",\"date\":\"" + today
                        + "\",\"name\":\"31번째\",\"repeat\":{\"weekdays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}}",
                409);
        assertThat(day(today.plusDays(7)).at("/subjects/0/tasks")).hasSize(30);
    }

    @Test
    void stoppingMovedOccurrenceDoesNotRestoreOriginalDate() {
        long id = series(today, 1 << (today.getDayOfWeek().getValue() - 1));
        String key = "r:" + id + ":" + today;
        call("PUT", "/api/planner/tasks/" + key + "/date", "{\"date\":\"" + today.plusDays(1) + "\"}", 200);
        call("PUT", "/api/planner/tasks/" + key + "/repeat", "{\"weekdays\":[]}", 200);
        assertThat(day(today).get("subjects")).isEmpty();
        assertThat(day(today.plusDays(1)).at("/subjects/0/tasks")).hasSize(1);
    }

    @Test
    void deletingForwardPreservesCompletedOccurrenceMovedBeforeBoundary() {
        long id = series(today, 127);
        String future = "r:" + id + ":" + today.plusDays(5);
        call("DELETE", "/api/planner/tasks/r:" + id + ":" + today, null, 204);
        call("PUT", "/api/planner/tasks/" + future + "/date", "{\"date\":\"" + today + "\"}", 200);
        call("PATCH", "/api/planner/tasks/" + future, "{\"done\":true}", 200);
        call("DELETE", "/api/planner/tasks/r:" + id + ":" + today.plusDays(2) + "?scope=FUTURE", null, 204);
        assertThat(day(today).at("/subjects/0/tasks/0/done").asBoolean()).isTrue();
        assertThat(day(today.plusDays(2)).get("subjects")).isEmpty();
    }

    @Test
    void globallyDeletedSubjectDoesNotLingerInFutureSelection() {
        LocalDate future = today.plusDays(2);
        call("PUT", "/api/planner/days/" + future + "/subjects/" + subject, null, 204);
        call("DELETE", "/api/subjects/" + subject, null, 204);
        assertThat(day(future).get("subjects")).isEmpty();
    }
}
