package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PlannerCarryApiTest extends PlannerApiSupport {
    @Test
    void obsoleteStoredTodayDoesNotSuppressOlderValidCandidate() {
        long series = jdbc.queryForObject(
                "INSERT INTO planner_series(user_id,subject_id) VALUES (?,?) RETURNING id", Long.class, user, subject);
        jdbc.update(
                "INSERT INTO planner_repeat_rule(series_id,name,start_date,end_date,weekdays) VALUES (?,'이전 규칙',?,?,127)",
                series,
                today.minusDays(10),
                today.minusDays(3));
        jdbc.update(
                "INSERT INTO study_task(subject_id,name,planned_date,series_id,occurrence_date) VALUES (?,'폐기된 오늘 회차',?,?,?)",
                subject,
                today,
                series,
                today);
        var result = candidates();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("date").asText())
                .isEqualTo(today.minusDays(3).toString());
    }

    private String past(String name, int daysAgo) {
        long id = jdbc.queryForObject(
                "INSERT INTO study_task(subject_id,name,planned_date) VALUES (?,?,?) RETURNING id",
                Long.class,
                subject,
                name,
                today.minusDays(daysAgo));
        return "t:" + id;
    }

    private JsonNode candidates() {
        return call("GET", "/api/planner/carryover", null, 200);
    }

    private String decision(String offered, String selected) {
        return "{\"offeredKeys\":" + offered + ",\"selectedKeys\":" + selected + "}";
    }

    @Test
    void bringsSelectedAndPermanentlyDismissesUnselectedWithoutLosingTheirDate() {
        String take = past("가져오기", 2);
        String leave = past("그대로", 1);
        assertThat(candidates()).hasSize(2);
        String body = decision("[\"" + take + "\",\"" + leave + "\"]", "[\"" + take + "\"]");
        call("POST", "/api/planner/carryover", body, 200);
        assertThat(candidates()).isEmpty();
        assertThat(day(today).at("/subjects/0/tasks/0/key").asText()).isEqualTo(take);
        assertThat(day(today.minusDays(1)).at("/subjects/0/tasks/0/key").asText())
                .isEqualTo(leave);
        call("POST", "/api/planner/carryover", body, 200);
        assertThat(day(today).at("/subjects/0/tasks")).hasSize(1);
    }

    @Test
    void failedImportRollsBackMovedAndDismissedStatesTogether() {
        String take = past("가져오기", 2);
        String leave = past("그대로", 1);
        for (int i = 0; i < 30; i++) add(today, "오늘" + i);
        assertThat(call(
                                "POST",
                                "/api/planner/carryover",
                                decision("[\"" + take + "\",\"" + leave + "\"]", "[\"" + take + "\"]"),
                                409)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_TASK_LIMIT");
        assertThat(candidates()).hasSize(2);
        assertThat(day(today.minusDays(2)).at("/subjects/0/tasks/0/key").asText())
                .isEqualTo(take);
    }

    @Test
    void repeatingCandidatesUseLatestUnfinishedOccurrenceAndSkipTodaysSchedule() {
        String weekday = today.minusDays(1).getDayOfWeek().toString();
        long series = jdbc.queryForObject(
                "INSERT INTO planner_series(user_id,subject_id) VALUES (?,?) RETURNING id", Long.class, user, subject);
        int mask = 1 << (today.minusDays(1).getDayOfWeek().getValue() - 1);
        jdbc.update(
                "INSERT INTO planner_repeat_rule(series_id,name,start_date,weekdays) VALUES (?,'반복',?,?)",
                series,
                today.minusDays(30),
                mask);
        JsonNode candidate = candidates();
        assertThat(candidate).hasSize(1);
        assertThat(candidate.get(0).get("date").asText())
                .isEqualTo(today.minusDays(1).toString());
        String key = candidate.get(0).get("key").asText();
        call("POST", "/api/planner/carryover", decision("[\"" + key + "\"]", "[]"), 200);
        assertThat(candidates()).isEmpty();
        jdbc.update("UPDATE planner_repeat_rule SET weekdays=127 WHERE series_id=?", series);
        assertThat(candidates()).isEmpty();
    }

    @Test
    void rejectsForeignOrUnOfferedSelectionAndStaleCompletedTask() {
        String key = past("후보", 1);
        call("POST", "/api/planner/carryover", decision("[]", "[\"" + key + "\"]"), 400);
        long me = user;
        user = newUser();
        call("POST", "/api/planner/carryover", decision("[\"" + key + "\"]", "[]"), 404);
        user = me;
        call("PATCH", "/api/planner/tasks/" + key, "{\"done\":true}", 200);
        assertThat(call("POST", "/api/planner/carryover", decision("[\"" + key + "\"]", "[\"" + key + "\"]"), 409)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_CARRYOVER_CHANGED");
        assertThat(day(today.minusDays(1)).at("/subjects/0/tasks/0/done").asBoolean())
                .isTrue();
    }

    @Test
    void rejectsCandidateMovedAwayAfterItWasOffered() {
        String key = past("옮겨질 후보", 1);
        assertThat(candidates()).hasSize(1);
        call("PUT", "/api/planner/tasks/" + key + "/date", "{\"date\":\"" + today.plusDays(1) + "\"}", 200);
        assertThat(call("POST", "/api/planner/carryover", decision("[\"" + key + "\"]", "[]"), 409)
                        .get("code")
                        .asText())
                .isEqualTo("PLANNER_CARRYOVER_CHANGED");
    }

    @Test
    void obsoleteMaterializedOccurrenceDoesNotReappearAsUnfinishedCandidate() {
        long series = jdbc.queryForObject(
                "INSERT INTO planner_series(user_id,subject_id) VALUES (?,?) RETURNING id", Long.class, user, subject);
        jdbc.update(
                "INSERT INTO planner_repeat_rule(series_id,name,start_date,end_date,weekdays) VALUES (?,'이전 규칙',?,?,127)",
                series,
                today.minusDays(10),
                today.minusDays(3));
        jdbc.update(
                "INSERT INTO study_task(subject_id,name,planned_date,series_id,occurrence_date) VALUES (?,'규칙 변경으로 없어진 회차',?,?,?)",
                subject,
                today.minusDays(1),
                series,
                today.minusDays(1));
        var result = candidates();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("date").asText())
                .isEqualTo(today.minusDays(3).toString());
    }
}
