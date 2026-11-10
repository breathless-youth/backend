package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlannerLegacyApiTest extends PlannerApiSupport {
    @Test
    void legacyAddUsesTodaysLimitRatherThanOtherDates() {
        for (int i = 0; i < 30; i++) add(today.plusDays(1), "내일" + i);
        call("POST", "/api/subjects/" + subject + "/tasks", "{\"name\":\"오늘 세션\"}", 201);
        assertThat(day(today).at("/subjects/0/tasks/0/name").asText()).isEqualTo("오늘 세션");
        var list = call("GET", "/api/subjects", null, 200);
        assertThat(list.get(0).get("tasks")).hasSize(1);
    }

    @Test
    void legacyAddCountsVirtualOccurrencesAndLegacyPatchCannotCompleteFuture() {
        for (int i = 0; i < 30; i++) {
            call(
                    "POST",
                    "/api/planner/tasks",
                    "{\"subjectId\":" + subject + ",\"date\":\"" + today + "\",\"name\":\"반복" + i
                            + "\",\"repeat\":{\"weekdays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}}",
                    201);
        }
        assertThat(call("POST", "/api/subjects/" + subject + "/tasks", "{\"name\":\"넘침\"}", 409)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_TASK_LIMIT");
    }

    @Test
    void oldTaskEndpointHonorsFutureCompletionAndPastRenameRules() {
        String key = add(today.plusDays(1), "미래");
        String id = key.substring(2);
        call("PATCH", "/api/subjects/" + subject + "/tasks/" + id, "{\"done\":true}", 400);
        jdbc.update("UPDATE study_task SET planned_date=? WHERE id=?", today.minusDays(1), Long.valueOf(id));
        assertThat(call("PATCH", "/api/subjects/" + subject + "/tasks/" + id, "{\"name\":\"지난 계획 수정\"}", 400)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_PAST_DAY");
        call("PATCH", "/api/subjects/" + subject + "/tasks/" + id, "{\"done\":true}", 200);
    }
}
