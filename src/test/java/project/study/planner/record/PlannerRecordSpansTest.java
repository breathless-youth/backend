package project.study.planner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import project.study.common.exception.BadRequestException;
import project.study.studysession.entity.StudySessionSource;

class PlannerRecordSpansTest {
    private static final Instant START = Instant.parse("2026-10-03T19:30:12.123456Z");
    private static final Instant END = Instant.parse("2026-10-03T21:00:45.654321Z");
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @Test
    void createNormalizesBothEdgesToMinutes() {
        var span = PlannerRecordSpans.create(operation(START, END), NOW);
        assertThat(span.start()).isEqualTo(Instant.parse("2026-10-03T19:30:00Z"));
        assertThat(span.end()).isEqualTo(Instant.parse("2026-10-03T21:00:00Z"));
    }

    @Test
    void unchangedOrOmittedTimesKeepExistingPrecisionEvenForShortLegacyRecords() {
        Instant shortEnd = START.plusSeconds(20);
        var current = current(START, shortEnd);
        var omitted = PlannerRecordSpans.update(operation(null, null), current, NOW);
        var explicit = PlannerRecordSpans.update(operation(START, shortEnd), current, NOW);
        assertThat(omitted).isEqualTo(explicit);
        assertThat(omitted.start()).isEqualTo(START);
        assertThat(omitted.end()).isEqualTo(shortEnd);
    }

    @Test
    void onlyChangedEndIsNormalizedWhileStartKeepsMicroseconds() {
        var span = PlannerRecordSpans.update(operation(START, END.plusSeconds(60)), current(START, END), NOW);
        assertThat(span.start()).isEqualTo(START);
        assertThat(span.end()).isEqualTo(Instant.parse("2026-10-03T21:01:00Z"));
    }

    @Test
    void changingStartCannotCreateASubMinuteRecordAgainstPreciseEnd() {
        assertThatThrownBy(() -> PlannerRecordSpans.update(
                        operation(Instant.parse("2026-10-03T21:00:01Z"), END), current(START, END), NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @CsvSource({",2026-10-03T21:00:00Z", "2026-10-03T19:30:00Z,"})
    void updateRejectsASingleMissingEdge(Instant start, Instant end) {
        assertThatThrownBy(() -> PlannerRecordSpans.update(operation(start, end), current(START, END), NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-03T21:00:01Z,2026-10-03T21:00:59Z",
        "2026-10-03T21:01:00Z,2026-10-03T21:00:00Z",
        "2026-10-03T21:00:00Z,2026-10-04T21:01:00Z",
        "2026-10-05T00:00:00Z,2026-10-05T00:01:00Z"
    })
    void changedTimesRejectShortReversedOverlongAndFutureSpans(Instant start, Instant end) {
        assertThatThrownBy(() -> PlannerRecordSpans.update(operation(start, end), current(START, END), NOW))
                .isInstanceOf(BadRequestException.class);
    }

    private static PlannerRecordOperation operation(Instant start, Instant end) {
        return new PlannerRecordOperation("test", "UPDATE", "c:" + START, start, end, null, null);
    }

    private static PlannerRecordEdit current(Instant start, Instant end) {
        return new PlannerRecordEdit("c:" + START, StudySessionSource.MEASURED, start, end, null, false, true);
    }
}
