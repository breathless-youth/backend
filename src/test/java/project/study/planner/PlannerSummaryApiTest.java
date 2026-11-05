package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** 플래너의 날짜 창을 자정 통계나 저장된 시간대로 고정하면 이 조회 계약이 깨진다. */
class PlannerSummaryApiTest extends PlannerApiSupport {

    @Test
    void 미래를_포함한_7일을_빈_날짜까지_채우고_계획과_개인_공부와_목표를_요약한다() {
        LocalDate from = today.minusDays(3);
        LocalDate future = today.plusDays(2);
        planned(subject, from, true);
        planned(subject, from, false);
        planned(subject, future, false);
        jdbc.update("INSERT INTO planner_default_goal(user_id,goal_sec) VALUES (?,3600)", user);
        jdbc.update("INSERT INTO planner_daily_goal(user_id,goal_date,goal_sec) VALUES (?,?,0)", user, from);
        jdbc.update("INSERT INTO planner_daily_goal(user_id,goal_date,goal_sec) VALUES (?,?,7200)", user, future);
        Instant start = from.atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        long first = measured(user, subject, start, start.plusSeconds(3600), 3600, 3000);
        event(first, "PHONE", start.plusSeconds(1200), start.plusSeconds(1800));
        Instant phoneStart =
                from.plusDays(1).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        long phone = measured(user, subject, phoneStart, phoneStart.plusSeconds(600), 600, 0);
        event(phone, "PHONE", phoneStart, phoneStart.plusSeconds(600));

        JsonNode result = summary(from, today.plusDays(3), "Asia/Seoul");

        assertThat(result).hasSize(7);
        assertThat(result.get(0)).isEqualTo(json.readTree("""
                {"date":"%s","taskCount":2,"completedCount":1,"studySec":3600,"focusSec":3000,
                 "studied":true,"hasTasks":true,"goalSec":0}
                """.formatted(from)));
        assertThat(result.get(1)).isEqualTo(json.readTree("""
                {"date":"%s","taskCount":0,"completedCount":0,"studySec":600,"focusSec":0,
                 "studied":false,"hasTasks":false,"goalSec":3600}
                """.formatted(from.plusDays(1))));
        assertThat(result.get(5)).isEqualTo(json.readTree("""
                {"date":"%s","taskCount":1,"completedCount":0,"studySec":0,"focusSec":0,
                 "studied":false,"hasTasks":true,"goalSec":7200}
                """.formatted(future)));
        for (int index : new int[] {2, 3, 4, 6}) {
            assertThat(result.get(index)).isEqualTo(json.readTree("""
                    {"date":"%s","taskCount":0,"completedCount":0,"studySec":0,"focusSec":0,
                     "studied":false,"hasTasks":false,"goalSec":3600}
                    """.formatted(from.plusDays(index))));
        }
    }

    @Test
    void 선택_할일_기록의_과목을_합치고_기록만_있는_삭제_과목도_보여준다() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        long taskOnly = subject(user, "할 일 과목", false);
        long recordedDeleted = subject(user, "삭제된 기록 과목", true);
        jdbc.update(
                "INSERT INTO planner_day_subject(user_id,plan_date,subject_id,sort_order) VALUES (?,?,?,0)",
                user,
                date,
                subject);
        planned(subject, date, false);
        planned(taskOnly, date, false);
        measured(
                user,
                recordedDeleted,
                Instant.parse("2026-10-01T05:00:00Z"),
                Instant.parse("2026-10-01T05:10:00Z"),
                600,
                600);

        JsonNode result = day(date);

        assertThat(result.get("subjects")).hasSize(3);
        assertThat(subjectIds(result)).containsExactlyInAnyOrder(subject, taskOnly, recordedDeleted);
        assertThat(findSubject(result, subject).get("selected").asBoolean()).isTrue();
        assertThat(findSubject(result, subject).get("tasks")).hasSize(1);
        assertThat(findSubject(result, taskOnly).get("selected").asBoolean()).isFalse();
        assertThat(findSubject(result, taskOnly).get("tasks")).hasSize(1);
        JsonNode deleted = findSubject(result, recordedDeleted);
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.get("selected").asBoolean()).isFalse();
        assertThat(deleted.get("tasks")).isEmpty();
        assertThat(deleted.get("studySec").asLong()).isEqualTo(600);
        assertThat(deleted.get("focusSec").asLong()).isEqualTo(600);
        assertThat(result.get("records")).hasSize(1);
        assertThat(result.get("focusSec").asLong()).isEqualTo(600);
    }

    @Test
    void 현지_05시를_가로지른_기록과_비공부_이벤트를_각_날짜와_과목에_정확히_나눈다() {
        Instant start = Instant.parse("2026-09-30T19:50:00Z");
        long id = measured(user, subject, start, start.plusSeconds(1200), 1080, 780);
        event(id, "PHONE", start.plusSeconds(300), start.plusSeconds(600));
        event(id, "PAUSE", start.plusSeconds(900), start.plusSeconds(1020));

        JsonNode before = day(LocalDate.of(2026, 9, 30));
        JsonNode after = day(LocalDate.of(2026, 10, 1));

        assertTimes(before, 600, 300);
        assertTimes(after, 480, 480);
        assertTimes(findSubject(before, subject), 600, 300);
        assertTimes(findSubject(after, subject), 480, 480);
        assertThat(before.at("/records/0/startedAt").asText()).isEqualTo("2026-09-30T19:50:00Z");
        assertThat(before.at("/records/0/endedAt").asText()).isEqualTo("2026-09-30T20:00:00Z");
        assertThat(after.at("/records/0/startedAt").asText()).isEqualTo("2026-09-30T20:00:00Z");
        assertThat(after.at("/records/0/endedAt").asText()).isEqualTo("2026-09-30T20:10:00Z");
        JsonNode summary = summary(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), "Asia/Seoul");
        assertTimes(summary.get(0), 600, 300);
        assertTimes(summary.get(1), 480, 480);
    }

    @Test
    void 요청_시간대로_창을_조회하되_저장된_사용자_시간대는_바꾸지_않는다() {
        measured(
                user,
                subject,
                Instant.parse("2026-10-01T07:00:00Z"),
                Instant.parse("2026-10-01T08:00:00Z"),
                3600,
                3600);

        JsonNode newYork = call("GET", "/api/planner/days/2026-09-30?timeZone=America/New_York", null, 200);
        JsonNode nextDay = call("GET", "/api/planner/days/2026-10-01?timeZone=America/New_York", null, 200);
        JsonNode range = summary(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), "America/New_York");
        JsonNode fallback = call("GET", "/api/planner/days/2026-10-01?timeZone=Not/AZone", null, 200);

        assertThat(newYork.get("timeZone").asText()).isEqualTo("America/New_York");
        assertTimes(newYork, 3600, 3600);
        assertTimes(nextDay, 0, 0);
        assertTimes(range.get(0), 3600, 3600);
        assertTimes(range.get(1), 0, 0);
        assertThat(fallback.get("timeZone").asText()).isEqualTo("Asia/Seoul");
        assertTimes(fallback, 3600, 3600);
        assertThat(jdbc.queryForObject("SELECT time_zone FROM users WHERE id=?", String.class, user))
                .isEqualTo("Asia/Seoul");
    }

    @ParameterizedTest
    @CsvSource({
        "2025-03-08,2025-03-09T08:30:00Z,2025-03-09T09:30:00Z",
        "2025-11-01,2025-11-02T09:30:00Z,2025-11-02T10:30:00Z"
    })
    void DST_23시간과_25시간_날도_다음_달력_날짜의_05시에서_분리한다(LocalDate date, Instant start, Instant end) {
        measured(user, subject, start, end, 3600, 3600);

        JsonNode first = call("GET", "/api/planner/days/" + date + "?timeZone=America/New_York", null, 200);
        JsonNode second =
                call("GET", "/api/planner/days/" + date.plusDays(1) + "?timeZone=America/New_York", null, 200);

        assertTimes(first, 1800, 1800);
        assertTimes(second, 1800, 1800);
        assertTimes(findSubject(first, subject), 1800, 1800);
        assertTimes(findSubject(second, subject), 1800, 1800);
    }

    @Test
    void 다른_사용자의_계획과_목표와_공부는_요약과_상세에_보이지_않는다() {
        long other = newUser();
        long otherSubject = subject(other, "다른 사람 과목", false);
        LocalDate date = LocalDate.of(2026, 10, 1);
        planned(otherSubject, date, true);
        jdbc.update("INSERT INTO planner_default_goal(user_id,goal_sec) VALUES (?,3600)", other);
        measured(
                other,
                otherSubject,
                Instant.parse("2026-10-01T07:00:00Z"),
                Instant.parse("2026-10-01T08:00:00Z"),
                3600,
                3600);

        JsonNode result = day(date);
        JsonNode summary = summary(date, date, "Asia/Seoul").get(0);

        assertThat(result.get("subjects")).isEmpty();
        assertThat(result.get("records")).isEmpty();
        assertTimes(result, 0, 0);
        assertThat(summary.get("taskCount").asInt()).isZero();
        assertThat(summary.get("completedCount").asInt()).isZero();
        assertThat(summary.get("goalSec").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/planner/summary?from=bad&to=2026-10-01",
                "/api/planner/summary?from=2026-10-02&to=2026-10-01",
                "/api/planner/summary?from=2025-01-01&to=2026-01-02",
                "/api/planner/summary?from=2026-10-01",
                "/api/planner?from=2026-10-02&to=2026-10-01",
                "/api/planner/days/bad"
            })
    void 잘못된_날짜와_역전_누락_초과_범위를_거절한다(String uri) {
        call("GET", uri, null, 400);
    }

    @Test
    void 요약과_상세와_기간_조회는_인증이_필요하다() {
        for (String uri : List.of(
                "/api/planner/summary?from=2026-10-01&to=2026-10-07",
                "/api/planner/days/2026-10-01",
                "/api/planner?from=2026-10-01&to=2026-10-07")) {
            assertThat(mvc.get().uri(uri).header("API-Version", "1")).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    private JsonNode summary(LocalDate from, LocalDate to, String zone) {
        return call("GET", "/api/planner/summary?from=" + from + "&to=" + to + "&timeZone=" + zone, null, 200);
    }

    private long subject(long owner, String name, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO study_subject(user_id,name,deleted_at)
                VALUES(?,?,CASE WHEN ? THEN now() END) RETURNING id
                """, Long.class, owner, name, deleted);
    }

    private void planned(long subjectId, LocalDate date, boolean done) {
        jdbc.update(
                "INSERT INTO study_task(subject_id,name,planned_date,done_at) VALUES (?,'할 일',?,?)",
                subjectId,
                date,
                done ? Timestamp.from(Instant.now()) : null);
    }

    private long measured(long owner, long subjectId, Instant start, Instant end, int study, int focus) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO study_session(user_id,stat_date,started_at,submission_started_at,ended_at,study_sec,focus_sec)
                VALUES(?,?,?,?,?,?,?) RETURNING id
                """,
                Long.class,
                owner,
                start.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(),
                Timestamp.from(start),
                Timestamp.from(start),
                Timestamp.from(end),
                study,
                focus);
        jdbc.update("""
                INSERT INTO study_session_subject_segment(session_id,subject_id,started_at,ended_at,study_sec,focus_sec)
                VALUES(?,?,?,?,?,?)
                """, id, subjectId, Timestamp.from(start), Timestamp.from(end), study, focus);
        return id;
    }

    private void event(long sessionId, String status, Instant from, Instant to) {
        jdbc.update(
                "INSERT INTO status_event(session_id,status,started_at,ended_at) VALUES(?,?,?,?)",
                sessionId,
                status,
                Timestamp.from(from),
                Timestamp.from(to));
    }

    private List<Long> subjectIds(JsonNode day) {
        List<Long> ids = new ArrayList<>();
        day.get("subjects").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private JsonNode findSubject(JsonNode day, long id) {
        for (JsonNode item : day.get("subjects")) {
            if (item.get("id").asLong() == id) return item;
        }
        throw new AssertionError("응답에 과목이 없습니다: " + id);
    }

    private void assertTimes(JsonNode node, long study, long focus) {
        assertThat(node.get("studySec").asLong()).isEqualTo(study);
        assertThat(node.get("focusSec").asLong()).isEqualTo(focus);
    }
}
