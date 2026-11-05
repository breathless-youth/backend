package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlannerPlanApiTest extends PlannerApiSupport {
    @Test
    void plansBelongOnlyToTheirDateAndLateCompletionStaysOnPlannedDate() {
        String key = add(today.plusDays(1), "내일 수학");
        assertThat(day(today).get("subjects")).isEmpty();
        assertThat(day(today.plusDays(1)).at("/subjects/0/tasks/0/name").asText())
                .isEqualTo("내일 수학");
        call("PATCH", "/api/planner/tasks/" + key, "{\"done\":true}", 400);
        jdbc.update(
                "UPDATE study_task SET planned_date = ? WHERE id = ?",
                today.minusDays(2),
                Long.parseLong(key.substring(2)));
        call("PATCH", "/api/planner/tasks/" + key, "{\"done\":true}", 200);
        assertThat(day(today.minusDays(2)).at("/subjects/0/tasks/0/done").asBoolean())
                .isTrue();
        assertThat(day(today).get("subjects")).isEmpty();
        call("PATCH", "/api/planner/tasks/" + key, "{\"done\":false}", 200);
        assertThat(day(today.minusDays(2)).at("/subjects/0/tasks/0/done").asBoolean())
                .isFalse();
    }

    @Test
    void failedMovePreservesOriginalAndCapacityIsPerDate() {
        String key = add(today, "옮길 일");
        for (int i = 0; i < 30; i++) add(today.plusDays(1), "할 일" + i);
        call("PUT", "/api/planner/tasks/" + key + "/date", "{\"date\":\"" + today.plusDays(1) + "\"}", 409);
        assertThat(day(today).at("/subjects/0/tasks/0/key").asText()).isEqualTo(key);
        call(
                "POST",
                "/api/planner/tasks",
                "{\"subjectId\":" + subject + ",\"date\":\"" + today.plusDays(1) + "\",\"name\":\"넘침\"}",
                409);
    }

    @Test
    void selectionOrderingAndRemovalAreDateSpecific() {
        long second = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?, '영어') RETURNING id", Long.class, user);
        call("PUT", "/api/planner/days/" + today + "/subjects/" + subject, null, 204);
        call("PUT", "/api/planner/days/" + today + "/subjects/" + second, null, 204);
        call(
                "PUT",
                "/api/planner/days/" + today + "/subjects/order",
                "{\"subjectIds\":[" + second + "," + subject + "]}",
                204);
        assertThat(day(today).at("/subjects/0/id").asLong()).isEqualTo(second);
        add(today, "함께 지워질 일");
        call("DELETE", "/api/planner/days/" + today + "/subjects/" + subject, null, 204);
        assertThat(day(today).get("subjects")).hasSize(1);
        assertThat(day(today.plusDays(1)).get("subjects")).isEmpty();
    }

    @Test
    void enforcesOwnershipPastEditsAndNewNameLimits() {
        String key = add(today, "정상");
        long owner = user;
        user = newUser();
        call("PATCH", "/api/planner/tasks/" + key, "{\"name\":\"남의 일\"}", 404);
        user = owner;
        call(
                "POST",
                "/api/planner/tasks",
                "{\"subjectId\":" + subject + ",\"date\":\"" + today.minusDays(1) + "\",\"name\":\"과거\"}",
                400);
        call(
                "POST",
                "/api/planner/tasks",
                "{\"subjectId\":" + subject + ",\"date\":\"" + today + "\",\"name\":\"" + "가".repeat(26) + "\"}",
                400);
        call("POST", "/api/subjects", "{\"name\":\"" + "가".repeat(13) + "\"}", 400);
        call("PUT", "/api/planner/tasks/" + key + "/date", "{\"date\":\"" + today.minusDays(1) + "\"}", 400);
    }
}
