package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** 분 범위 과목 변경·삭제(BY-1204). 조회는 실제 조각과 revision을 주고, 저장은 조각과의 교집합에만 적용한다. */
class PlannerRecordRangeApiTest extends PlannerRecordRangeSupport {
    @Test
    void readExposesActualPiecesAndZeroRevisionForUneditedRecord() {
        long b = newSubject("영어");
        long c = newSubject("과학");
        threeSubjectsInFirstMinute(subject, b, c);

        JsonNode record = record();

        assertThat(record.get("revision").asLong()).isZero();
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:00:20Z=" + subject
                        + " 2026-10-01T03:00:20Z~2026-10-01T03:00:40Z=" + b
                        + " 2026-10-01T03:00:40Z~2026-10-01T03:01:00Z=" + c
                        + " 2026-10-01T03:01:00Z~2026-10-01T03:03:00Z=" + subject);
    }

    @Test
    void sessionTimeWithoutSubjectSegmentIsPieceWithoutSubject() {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:02:00Z", 120, 120);
        segment(id, subject, "2026-10-01T03:01:00Z", "2026-10-01T03:02:00Z");

        assertThat(pieces(record()))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:01:00Z=none 2026-10-01T03:01:00Z~2026-10-01T03:02:00Z="
                        + subject);
    }

    @Test
    void subjectRangeRepaintsEveryPieceInMinuteAndKeepsNeighboursAndTotals() {
        long b = newSubject("영어");
        long c = newSubject("과학");
        long target = newSubject("국어");
        threeSubjectsInFirstMinute(subject, b, c);

        JsonNode result = batch(editRanges(
                        "range-subject", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", target)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("key").asText()).isEqualTo(KEY);
        assertThat(result.get("revision").asLong()).isEqualTo(1);
        JsonNode record = record();
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:01:00Z=" + target
                        + " 2026-10-01T03:01:00Z~2026-10-01T03:03:00Z=" + subject);
        assertThat(record.get("studySec").asLong()).isEqualTo(180);
        assertThat(record.get("focusSec").asLong()).isEqualTo(150);
        assertThat(record.get("revision").asLong()).isEqualTo(1);
        assertThat(record.get("edited").asBoolean()).isTrue();
        assertThat(record.get("subjectSegments")).hasSize(2);
    }

    @Test
    void subjectNullKeepsTimeWhileDeleteRemovesIt() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:02:00Z", 120, 120);

        batch(editRanges("range-none", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", null)));
        JsonNode none = record();
        assertThat(none.get("studySec").asLong()).isEqualTo(120);
        assertThat(pieces(none)).isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:02:00Z=none");

        batch(editRanges("range-delete", 1, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "DELETE", null)));
        JsonNode deleted = record();
        assertThat(deleted.get("studySec").asLong()).isEqualTo(60);
        assertThat(pieces(deleted)).isEqualTo("2026-10-01T03:01:00Z~2026-10-01T03:02:00Z=none");
        assertThat(deleted.get("startedAt").asText()).isEqualTo("2026-10-01T03:01:00Z");
    }

    @Test
    void deletingMiddleMinuteKeepsExactEdgesAndOriginalsAndStaysDeletedAfterWholeUpdate() {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:03:37.5Z", 217, 217);
        segment(id, subject, "2026-10-01T03:00:00Z", "2026-10-01T03:03:37.5Z");

        batch(editRanges("range-middle", 0, range("2026-10-01T03:01:00Z", "2026-10-01T03:02:00Z", "DELETE", null)));

        JsonNode record = record();
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:01:00Z=" + subject
                        + " 2026-10-01T03:02:00Z~2026-10-01T03:03:37.500Z=" + subject);
        assertThat(record.get("fullEndedAt").asText()).isEqualTo("2026-10-01T03:03:37.500Z");
        assertThat(record.get("studySec").asLong()).isEqualTo(157);
        assertThat(jdbc.queryForObject("SELECT study_sec FROM study_session WHERE id=?", Integer.class, id))
                .isEqualTo(217);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM study_session_subject_segment WHERE session_id=?", Integer.class, id))
                .isEqualTo(1);

        long other = newSubject("영어");
        batch("{\"operationId\":\"whole-subject\",\"action\":\"UPDATE\",\"key\":\"" + KEY + "\",\"subjectId\":" + other
                + "}");
        assertThat(pieces(record()))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:01:00Z=" + other
                        + " 2026-10-01T03:02:00Z~2026-10-01T03:03:37.500Z=" + other);
        assertThat(record().get("studySec").asLong()).isEqualTo(157);

        batch("{\"operationId\":\"whole-time\",\"action\":\"UPDATE\",\"key\":\"" + KEY
                + "\",\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:05:00Z\",\"subjectId\":"
                + other + "}");
        JsonNode extended = record();
        assertThat(pieces(extended))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:01:00Z=" + other
                        + " 2026-10-01T03:02:00Z~2026-10-01T03:05:00Z=" + other);
        assertThat(extended.get("studySec").asLong()).isEqualTo(240);
    }

    @Test
    void paintingMovedRecordKeepsRecomputedTotalsDespiteFractionalEvents() {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 120);
        jdbc.update(
                "INSERT INTO status_event(session_id,status,started_at,ended_at) VALUES (?,'PHONE',?,?)",
                id,
                ts("2026-10-01T03:00:30.5Z"),
                ts("2026-10-01T03:01:30.5Z"));
        batch("{\"operationId\":\"move\",\"action\":\"UPDATE\",\"key\":\"" + KEY
                + "\",\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:04:00Z\"}");
        long focus = record().get("focusSec").asLong();
        long study = record().get("studySec").asLong();

        batch(editRanges("range-moved", 1, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", subject)));

        assertThat(record().get("focusSec").asLong()).isEqualTo(focus);
        assertThat(record().get("studySec").asLong()).isEqualTo(study);
    }

    @Test
    void subjectSegmentsNeverGoNegativeWhenSplitInsideFractionalEvent() {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00.5Z", 180, 150);
        jdbc.update(
                "INSERT INTO status_event(session_id,status,started_at,ended_at) VALUES (?,'PHONE',?,?)",
                id,
                ts("2026-10-01T03:02:30.5Z"),
                ts("2026-10-01T03:03:00.5Z"));
        batch("{\"operationId\":\"move-frac\",\"action\":\"UPDATE\",\"key\":\"" + KEY
                + "\",\"startedAt\":\"2026-10-01T02:59:00Z\",\"endedAt\":\"2026-10-01T03:03:00.5Z\"}");
        long other = newSubject("영어");
        long focus = record().get("focusSec").asLong();

        batch(editRanges("range-frac", 1, range("2026-10-01T03:03:00Z", "2026-10-01T03:04:00Z", "SUBJECT", other)));

        JsonNode record = record();
        assertThat(record.get("focusSec").asLong()).isEqualTo(focus);
        record.get("subjectSegments").forEach(segment -> {
            assertThat(segment.get("focusSec").asLong()).isNotNegative();
            assertThat(segment.get("studySec").asLong()).isNotNegative();
        });
    }

    @Test
    void multipleRangesApplyAtomicallyAndStaleRangeRejectsAll() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);
        long other = newSubject("영어");

        JsonNode failed = batch(editRanges(
                        "range-atomic",
                        0,
                        range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", other),
                        range("2026-10-01T03:10:00Z", "2026-10-01T03:11:00Z", "DELETE", null)))
                .get(0);

        assertThat(failed.get("status").asInt()).isEqualTo(409);
        assertThat(failed.get("code").asText()).isEqualTo("STALE_RECORD");
        JsonNode record = record();
        assertThat(record.get("revision").asLong()).isZero();
        assertThat(pieces(record)).isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:03:00Z=none");
    }

    @Test
    void staleRevisionConflictsAndSameOperationReplaysFirstResult() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);
        String first =
                editRanges("range-retry", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "DELETE", null));

        assertThat(batch(first).get(0).get("revision").asLong()).isEqualTo(1);
        JsonNode replay = batch(first).get(0);
        assertThat(replay.get("success").asBoolean()).isTrue();
        assertThat(replay.get("revision").asLong()).isEqualTo(1);
        assertThat(pieces(record())).isEqualTo("2026-10-01T03:01:00Z~2026-10-01T03:03:00Z=none");

        JsonNode stale = batch(editRanges(
                        "range-stale", 0, range("2026-10-01T03:02:00Z", "2026-10-01T03:03:00Z", "DELETE", null)))
                .get(0);
        assertThat(stale.get("status").asInt()).isEqualTo(409);
        assertThat(stale.get("code").asText()).isEqualTo("STALE_RECORD");

        JsonNode staleUpdate = batch("{\"operationId\":\"update-stale\",\"action\":\"UPDATE\",\"key\":\"" + KEY
                        + "\",\"revision\":0,\"subjectId\":null}")
                .get(0);
        assertThat(staleUpdate.get("code").asText()).isEqualTo("STALE_RECORD");
        assertThat(record().get("revision").asLong()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // 분 단위가 아닌 시각
                "{\"revision\":0,\"ranges\":[{\"startedAt\":\"2026-10-01T03:00:30Z\",\"endedAt\":\"2026-10-01T03:01:00Z\",\"change\":\"DELETE\"}]}",
                // 범위끼리 겹침
                "{\"revision\":0,\"ranges\":[{\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:02:00Z\",\"change\":\"DELETE\"},{\"startedAt\":\"2026-10-01T03:01:00Z\",\"endedAt\":\"2026-10-01T03:03:00Z\",\"change\":\"DELETE\"}]}",
                // DELETE에 과목
                "{\"revision\":0,\"ranges\":[{\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:01:00Z\",\"change\":\"DELETE\",\"subjectId\":1}]}",
                // 알 수 없는 change
                "{\"revision\":0,\"ranges\":[{\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:01:00Z\",\"change\":\"MOVE\"}]}",
                // revision 누락
                "{\"ranges\":[{\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:01:00Z\",\"change\":\"DELETE\"}]}",
                // 빈 범위
                "{\"revision\":0,\"ranges\":[]}",
                // 전체 시각을 함께 보냄
                "{\"revision\":0,\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:01:00Z\",\"ranges\":[{\"startedAt\":\"2026-10-01T03:00:00Z\",\"endedAt\":\"2026-10-01T03:01:00Z\",\"change\":\"DELETE\"}]}"
            })
    void invalidRangeRequestsAreItemBadRequests(String fields) {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);
        String operation = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"action\":\"EDIT_RANGES\",\"key\":\"" + KEY
                + "\"," + fields.substring(1);

        JsonNode result = batch(operation).get(0);

        assertThat(result.get("status").asInt()).isEqualTo(400);
        assertThat(record().get("revision").asLong()).isZero();
    }

    @Test
    void anotherUsersSubjectIsRejected() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);
        long stranger = newUser();
        long foreign = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?,'남의 과목') RETURNING id", Long.class, stranger);

        JsonNode result = batch(editRanges(
                        "range-foreign", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", foreign)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isFalse();
        assertThat(record().get("revision").asLong()).isZero();
    }

    @Test
    void operationSavedBeforeRangeFieldsStillReplaysWithSameHash() throws Exception {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);
        // 범위 수정 이전 서버가 저장한 본문 모양(null 포함, 필드 순서 그대로)의 해시
        String before = "{\"operationId\":\"legacy-op\",\"action\":\"DELETE\",\"key\":\"" + KEY
                + "\",\"startedAt\":null,\"endedAt\":null,\"subjectId\":null,\"timeZone\":null}";
        String hash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(before.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update(
                "INSERT INTO planner_record_operation(user_id,operation_id,payload_hash,result_json) VALUES (?,?,?,?)",
                user,
                "legacy-op",
                hash,
                "{\"operationId\":\"legacy-op\",\"success\":true,\"key\":\"" + KEY
                        + "\",\"status\":200,\"code\":null,\"message\":null}");

        JsonNode replay = batch("{\"operationId\":\"legacy-op\",\"action\":\"DELETE\",\"key\":\"" + KEY + "\"}")
                .get(0);

        assertThat(replay.get("success").asBoolean()).isTrue();
        assertThat(replay.get("revision").isNull()).isTrue();
        assertThat(day(DAY).get("records")).hasSize(1);
    }

    @Test
    void rangesOnOtherActionsAreRejectedInsteadOfIgnored() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);

        JsonNode result = batch("{\"operationId\":\"delete-with-ranges\",\"action\":\"DELETE\",\"key\":\"" + KEY
                        + "\",\"ranges\":[" + range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "DELETE", null)
                        + "]}")
                .get(0);

        assertThat(result.get("status").asInt()).isEqualTo(400);
        assertThat(record().get("revision").asLong()).isZero();
    }

    @Test
    void updateIntoDeletedGapOnlyIsRejectedInsteadOfHidingRecord() {
        session("2026-10-01T03:00:00Z", "2026-10-01T03:03:00Z", 180, 180);
        batch(editRanges("gap", 0, range("2026-10-01T03:01:00Z", "2026-10-01T03:02:00Z", "DELETE", null)));

        JsonNode result = batch(
                        "{\"operationId\":\"into-gap\",\"action\":\"UPDATE\",\"key\":\"" + KEY
                                + "\",\"startedAt\":\"2026-10-01T03:01:00Z\",\"endedAt\":\"2026-10-01T03:02:00Z\",\"subjectId\":null}")
                .get(0);

        assertThat(result.get("status").asInt()).isEqualTo(400);
        assertThat(record().get("revision").asLong()).isEqualTo(1);
    }

    @Test
    void createAtStartOfTrimmedManualRecordDoesNotOverwriteIt() {
        batch("{\"operationId\":\"manual\",\"action\":\"CREATE\",\"startedAt\":\"2026-10-01T03:00:00Z\","
                + "\"endedAt\":\"2026-10-01T03:05:00Z\",\"subjectId\":null}");
        String key = "m:2026-10-01T03:00:00Z";
        batch("{\"operationId\":\"trim\",\"action\":\"EDIT_RANGES\",\"key\":\"" + key + "\",\"revision\":1,"
                + "\"ranges\":[" + range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "DELETE", null) + "]}");

        JsonNode result = batch(
                        "{\"operationId\":\"again\",\"action\":\"CREATE\",\"startedAt\":\"2026-10-01T03:00:00Z\","
                                + "\"endedAt\":\"2026-10-01T03:01:00Z\",\"subjectId\":null}")
                .get(0);

        assertThat(result.get("status").asInt()).isEqualTo(409);
        JsonNode kept = record();
        assertThat(kept.get("key").asText()).isEqualTo(key);
        assertThat(pieces(kept)).isEqualTo("2026-10-01T03:01:00Z~2026-10-01T03:05:00Z=none");
    }

    @Test
    void deletedSubjectAlreadyInRangeCanBeSentAgain() {
        long a = newSubject("국어");
        long b = newSubject("과학");
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:02:00Z", 120, 120);
        segment(id, a, "2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z");
        segment(id, b, "2026-10-01T03:01:00Z", "2026-10-01T03:02:00Z");
        jdbc.update("UPDATE study_subject SET deleted_at=now() WHERE id=?", a);

        JsonNode result = batch(
                        editRanges("same", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", a)))
                .get(0);

        assertThat(result.get("status").asInt()).isEqualTo(200);
    }
}
