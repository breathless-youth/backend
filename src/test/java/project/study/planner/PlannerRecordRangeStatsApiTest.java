package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 분 범위 수정 뒤 개인 기록 통계·리듬이 남은 조각만 세는지 확인한다(BY-1204). */
class PlannerRecordRangeStatsApiTest extends PlannerRecordRangeSupport {
    @Test
    void personalStatsUseRemainingPiecesAndDropDeletedEvents() {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 120);
        jdbc.update(
                "INSERT INTO status_event(session_id,status,started_at,ended_at) VALUES (?,'PHONE',?,?)",
                id,
                ts("2026-10-01T03:01:00Z"),
                ts("2026-10-01T03:02:00Z"));

        batch(editRanges("range-event", 0, range("2026-10-01T03:01:00Z", "2026-10-01T03:02:00Z", "DELETE", null)));

        JsonNode stats = call("GET", "/api/planner/records/stats?date=" + DAY + "&timeZone=Asia/Seoul", null, 200);
        assertThat(stats.get("totalStudySec").asLong()).isEqualTo(120);
        assertThat(stats.get("totalFocusSec").asLong()).isEqualTo(120);
        assertThat(stats.at("/totalEventCounts/PHONE").asLong()).isZero();
    }

    @Test
    void rhythmSkipsDeletedMiddleHour() {
        session("2026-10-01T03:00:00Z", "2026-10-01T06:00:00Z", 10800, 10800);

        batch(editRanges("range-hour", 0, range("2026-10-01T04:00:00Z", "2026-10-01T05:00:00Z", "DELETE", null)));

        JsonNode grid = call("GET", "/api/planner/records/stats/rhythm?timeZone=UTC", null, 200)
                .get("focusSecByWeekdayHour");
        long[] hours = new long[24];
        grid.forEach(day -> {
            for (int hour = 0; hour < 24; hour++) {
                hours[hour] += day.get(hour).asLong();
            }
        });
        // 그리드는 세션 시간대 기준이다. 남은 두 시간만 3600초씩이고 지운 가운데 시간은 비어 있어야 한다.
        int first = java.util.stream.IntStream.range(0, 24)
                .filter(h -> hours[h] > 0)
                .findFirst()
                .orElseThrow();
        assertThat(new long[] {hours[first], hours[(first + 1) % 24], hours[(first + 2) % 24]})
                .containsExactly(3600, 0, 3600);
    }

    @Test
    void deletingEveryPieceRemovesRecordFromPlannerAndStats() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:01:30Z", 90, 90);

        JsonNode result = batch(editRanges(
                        "range-all", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:02:00Z", "DELETE", null)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(day(DAY).get("records")).isEmpty();
        JsonNode stats = call("GET", "/api/planner/records/stats?date=" + DAY + "&timeZone=Asia/Seoul", null, 200);
        assertThat(stats.get("sessionCount").asInt()).isZero();
    }
}
