package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlannerBoundaryApiTest extends PlannerApiSupport {
    @Test
    void readingGoalsDoesNotChangeDeviceTimeZone() {
        call("GET", "/api/planner/goals/default?timeZone=America/New_York", null, 200);
        call("GET", "/api/planner/goals/" + today + "?timeZone=Pacific/Auckland", null, 200);
        assertThat(jdbc.queryForObject("SELECT time_zone FROM users WHERE id=?", String.class, user))
                .isEqualTo("Asia/Seoul");
    }

    @Test
    void unsupportedDatesCannotBypassCapacityScanAndAreRejectedBeforeWrites() {
        call(
                "POST",
                "/api/planner/tasks",
                "{\"subjectId\":" + subject + ",\"date\":\"+10000-01-01\",\"name\":\"범위 밖\"}",
                400);
        call("GET", "/api/planner/days/+10000-01-01", null, 400);
        call("PUT", "/api/planner/goals/+10000-01-01", "{\"goalSec\":1800}", 400);
        call(
                "POST",
                "/api/planner/tasks",
                "{\"subjectId\":" + subject + ",\"date\":\"" + today
                        + "\",\"name\":\"끝 날짜 오류\",\"repeat\":{\"weekdays\":[\"MONDAY\"],\"endDate\":\"+10000-01-01\"}}",
                400);
        assertThat(day(today).get("subjects")).isEmpty();
    }
}
