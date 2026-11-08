package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

/** 분 범위 수정(BY-1204) 테스트 공통 준비. 2026-10-01 12:00 KST 전후의 측정 기록 하나를 고친다. */
abstract class PlannerRecordRangeSupport extends PlannerApiSupport {
    static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    static final String KEY = "c:2026-10-01T03:00:00Z";

    long session(String start, String end, int study, int focus) {
        return jdbc.queryForObject(
                "INSERT INTO study_session(user_id,stat_date,started_at,submission_started_at,ended_at,study_sec,focus_sec) VALUES (?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                user,
                DAY,
                ts(start),
                ts(start),
                ts(end),
                study,
                focus);
    }

    void segment(long session, long subjectId, String start, String end) {
        jdbc.update(
                "INSERT INTO study_session_subject_segment(session_id,subject_id,started_at,ended_at,study_sec,focus_sec) VALUES (?,?,?,?,0,0)",
                session,
                subjectId,
                ts(start),
                ts(end));
    }

    long newSubject(String name) {
        return jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?,?) RETURNING id", Long.class, user, name);
    }

    static String range(String start, String end, String change, Long subjectId) {
        return "{\"startedAt\":\"" + start + "\",\"endedAt\":\"" + end + "\",\"change\":\"" + change + "\""
                + (subjectId == null ? "" : ",\"subjectId\":" + subjectId) + "}";
    }

    static String editRanges(String id, long revision, String... ranges) {
        return "{\"operationId\":\"" + id + "\",\"action\":\"EDIT_RANGES\",\"key\":\"" + KEY + "\",\"revision\":"
                + revision + ",\"ranges\":[" + String.join(",", ranges) + "]}";
    }

    JsonNode batch(String... operations) {
        return call(
                        "POST",
                        "/api/planner/records/batch",
                        "{\"operations\":[" + String.join(",", operations) + "]}",
                        200)
                .get("results");
    }

    JsonNode record() {
        JsonNode records = day(DAY).get("records");
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    static String pieces(JsonNode record) {
        StringBuilder text = new StringBuilder();
        record.get("pieces")
                .forEach(p -> text.append(p.get("startedAt").asText())
                        .append('~')
                        .append(p.get("endedAt").asText())
                        .append('=')
                        .append(
                                p.get("subjectId").isNull()
                                        ? "none"
                                        : p.get("subjectId").asText())
                        .append(' '));
        return text.toString().trim();
    }

    /** A 20초·B 20초·C 20초 뒤 A 2분. 공부 180초, 순공 150초. */
    long threeSubjectsInFirstMinute(long a, long b, long c) {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 150);
        segment(id, a, "2026-10-01T03:00:00Z", "2026-10-01T03:00:20Z");
        segment(id, b, "2026-10-01T03:00:20Z", "2026-10-01T03:00:40Z");
        segment(id, c, "2026-10-01T03:00:40Z", "2026-10-01T03:01:00Z");
        segment(id, a, "2026-10-01T03:01:00Z", "2026-10-01T03:03:00Z");
        return id;
    }

    static Timestamp ts(String value) {
        return Timestamp.from(Instant.parse(value));
    }
}
