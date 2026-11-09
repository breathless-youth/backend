package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 분 범위 빈 시간 채우기(BY-1215). 범위 안 빈 부분만 채우고 다른 과목 조각은 그대로 둔다. */
class PlannerRecordRangeAddApiTest extends PlannerRecordRangeSupport {
    private long korean;
    private long math;

    /** 국어 03:00–03:20, 수학 03:20–03:40. 공부·순공 2400초. */
    private void koreanThenMath() {
        korean = newSubject("국어");
        math = newSubject("수학");
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:40:00Z", 2400, 2400);
        segment(id, korean, "2026-10-01T03:00:00Z", "2026-10-01T03:20:00Z");
        segment(id, math, "2026-10-01T03:20:00Z", "2026-10-01T03:40:00Z");
    }

    @Test
    void addBeforeExtendsOneBlockAndKeepsOtherSubject() {
        koreanThenMath();

        JsonNode result = batch(editRanges(
                        "add-before", 0, range("2026-10-01T02:50:00Z", "2026-10-01T03:00:00Z", "ADD", korean)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(result.get("key").asText()).isEqualTo(KEY);
        assertThat(result.get("revision").asLong()).isEqualTo(1);
        JsonNode record = record();
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T02:50:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:40:00Z=" + math);
        assertThat(record.get("fullStartedAt").asText()).isEqualTo("2026-10-01T02:50:00Z");
        assertThat(record.get("fullEndedAt").asText()).isEqualTo("2026-10-01T03:40:00Z");
        assertThat(record.get("revision").asLong()).isEqualTo(1);
        assertThat(record.get("studySec").asLong()).isEqualTo(3000);
        assertThat(record.get("focusSec").asLong()).isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT study_sec FROM study_session WHERE user_id=?", Integer.class, user))
                .isEqualTo(2400);
    }

    @Test
    void addAfterExtendsEndAndKeepsOtherSubject() {
        koreanThenMath();

        batch(editRanges("add-after", 0, range("2026-10-01T03:40:00Z", "2026-10-01T03:50:00Z", "ADD", math)));

        JsonNode record = record();
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:50:00Z=" + math);
        assertThat(record.get("fullEndedAt").asText()).isEqualTo("2026-10-01T03:50:00Z");
    }

    @Test
    void addOverExistingPiecesFillsOnlyEmptyPart() {
        koreanThenMath();

        batch(editRanges("add-cover", 0, range("2026-10-01T03:35:00Z", "2026-10-01T03:45:00Z", "ADD", korean)));

        assertThat(pieces(record()))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:40:00Z=" + math
                        + " 2026-10-01T03:40:00Z~2026-10-01T03:45:00Z=" + korean);
    }

    @Test
    void addFullyCoveredRangeChangesNothingButSucceeds() {
        koreanThenMath();

        JsonNode result = batch(
                        editRanges("add-none", 0, range("2026-10-01T03:10:00Z", "2026-10-01T03:30:00Z", "ADD", null)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isTrue();
        JsonNode record = record();
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:40:00Z=" + math);
        assertThat(record.get("studySec").asLong()).isEqualTo(2400);
    }

    @Test
    void addOverlappingAnotherRecordConflictsAndSavesNothing() {
        koreanThenMath();
        batch("{\"operationId\":\"other\",\"action\":\"CREATE\",\"startedAt\":\"2026-10-01T03:45:00Z\","
                + "\"endedAt\":\"2026-10-01T03:50:00Z\",\"subjectId\":null}");

        JsonNode result = batch(editRanges(
                        "add-overlap",
                        0,
                        range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", math),
                        range("2026-10-01T03:40:00Z", "2026-10-01T03:50:00Z", "ADD", math)))
                .get(0);

        assertThat(result.get("status").asInt()).isEqualTo(409);
        assertThat(result.get("message").asText()).isEqualTo("다른 개인 기록과 시간이 겹칩니다");
        JsonNode kept = measured();
        assertThat(kept.get("revision").asLong()).isZero();
        assertThat(pieces(kept))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:40:00Z=" + math);
    }

    @Test
    void addTouchingAnotherRecordIsAllowed() {
        koreanThenMath();
        batch("{\"operationId\":\"other\",\"action\":\"CREATE\",\"startedAt\":\"2026-10-01T03:50:00Z\","
                + "\"endedAt\":\"2026-10-01T03:55:00Z\",\"subjectId\":null}");

        JsonNode result = batch(
                        editRanges("add-touch", 0, range("2026-10-01T03:40:00Z", "2026-10-01T03:50:00Z", "ADD", math)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isTrue();
        assertThat(measured().get("fullEndedAt").asText()).isEqualTo("2026-10-01T03:50:00Z");
    }

    @Test
    void addRefillsGapLeftByRangeDeletion() {
        koreanThenMath();
        batch(editRanges("gap", 0, range("2026-10-01T03:10:00Z", "2026-10-01T03:11:00Z", "DELETE", null)));

        batch(editRanges("refill", 1, range("2026-10-01T03:10:00Z", "2026-10-01T03:11:00Z", "ADD", korean)));

        JsonNode record = record();
        assertThat(record.get("revision").asLong()).isEqualTo(2);
        assertThat(pieces(record))
                .isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:40:00Z=" + math);
    }

    @Test
    void addWorksOnFourSecondRecord() {
        long id = session("2026-10-01T03:00:00Z", "2026-10-01T03:00:04Z", 4, 4);
        segment(id, subject, "2026-10-01T03:00:00Z", "2026-10-01T03:00:04Z");

        JsonNode result = batch(editRanges(
                        "add-short", 0, range("2026-10-01T03:00:00Z", "2026-10-01T03:02:00Z", "ADD", subject)))
                .get(0);

        assertThat(result.get("success").asBoolean()).isTrue();
        JsonNode record = record();
        assertThat(pieces(record)).isEqualTo("2026-10-01T03:00:00Z~2026-10-01T03:02:00Z=" + subject);
        assertThat(record.get("fullEndedAt").asText()).isEqualTo("2026-10-01T03:02:00Z");
        assertThat(record.get("studySec").asLong()).isEqualTo(120);
    }

    @Test
    void addMixesWithSubjectAndDeleteInOneOperation() {
        koreanThenMath();

        JsonNode result = batch(editRanges(
                        "add-mix",
                        0,
                        range("2026-10-01T02:59:00Z", "2026-10-01T03:00:00Z", "ADD", null),
                        range("2026-10-01T03:00:00Z", "2026-10-01T03:01:00Z", "SUBJECT", math),
                        range("2026-10-01T03:39:00Z", "2026-10-01T03:40:00Z", "DELETE", null)))
                .get(0);

        assertThat(result.get("revision").asLong()).isEqualTo(1);
        assertThat(pieces(record()))
                .isEqualTo("2026-10-01T02:59:00Z~2026-10-01T03:00:00Z=none"
                        + " 2026-10-01T03:00:00Z~2026-10-01T03:01:00Z=" + math
                        + " 2026-10-01T03:01:00Z~2026-10-01T03:20:00Z=" + korean
                        + " 2026-10-01T03:20:00Z~2026-10-01T03:39:00Z=" + math);
    }

    @Test
    void staleSiblingRangeRejectsAddToo() {
        koreanThenMath();

        JsonNode result = batch(editRanges(
                        "add-stale",
                        0,
                        range("2026-10-01T03:40:00Z", "2026-10-01T03:50:00Z", "ADD", math),
                        range("2026-10-01T04:10:00Z", "2026-10-01T04:11:00Z", "DELETE", null)))
                .get(0);

        assertThat(result.get("code").asText()).isEqualTo("STALE_RECORD");
        assertThat(record().get("fullEndedAt").asText()).isEqualTo("2026-10-01T03:40:00Z");
    }

    @Test
    void futureAndOverTwentyFourHoursAreRejected() {
        koreanThenMath();

        JsonNode future = batch(
                        editRanges("add-future", 0, range("2099-01-01T00:00:00Z", "2099-01-01T00:01:00Z", "ADD", math)))
                .get(0);
        JsonNode tooLong = batch(
                        editRanges("add-long", 0, range("2026-10-01T03:40:00Z", "2026-10-02T03:01:00Z", "ADD", math)))
                .get(0);

        assertThat(future.get("status").asInt()).isEqualTo(400);
        assertThat(future.get("message").asText()).isEqualTo("미래 기록은 입력할 수 없습니다");
        assertThat(tooLong.get("status").asInt()).isEqualTo(400);
        assertThat(tooLong.get("message").asText()).isEqualTo("기록은 1분 이상 24시간 이하여야 합니다");
        assertThat(record().get("revision").asLong()).isZero();
    }

    @Test
    void deletedSubjectCannotBeAddedIntoEmptyTime() {
        koreanThenMath();
        jdbc.update("UPDATE study_subject SET deleted_at=now() WHERE id=?", korean);

        JsonNode result = batch(editRanges(
                        "add-deleted", 0, range("2026-10-01T02:50:00Z", "2026-10-01T03:00:00Z", "ADD", korean)))
                .get(0);

        assertThat(result.get("status").asInt()).isEqualTo(400);
        assertThat(record().get("revision").asLong()).isZero();
    }

    private JsonNode measured() {
        for (JsonNode record : day(DAY).get("records")) {
            if (record.get("key").asText().equals(KEY)) {
                return record;
            }
        }
        throw new AssertionError("측정 기록이 없습니다");
    }
}
