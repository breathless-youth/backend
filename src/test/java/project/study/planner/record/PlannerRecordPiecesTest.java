package project.study.planner.record;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerRecordPiecesTest {
    private static Instant at(String time) {
        return Instant.parse("2026-10-01T" + time + "Z");
    }

    private static PlannerRecordPiece piece(String from, String to, Long subject) {
        return new PlannerRecordPiece(at(from), at(to), subject);
    }

    @Test
    void paintChangesOnlyTheIntersectionAndNeverCreatesTime() {
        List<PlannerRecordPiece> pieces = List.of(piece("03:00:00", "03:00:37", 1L));

        assertThat(PlannerRecordPieces.paint(pieces, at("03:00:00"), at("03:01:00"), 2L))
                .containsExactly(piece("03:00:00", "03:00:37", 2L));
        assertThat(PlannerRecordPieces.paint(pieces, at("03:00:20"), at("03:01:00"), 2L))
                .containsExactly(piece("03:00:00", "03:00:20", 1L), piece("03:00:20", "03:00:37", 2L));
    }

    @Test
    void removeSplitsAroundTheRangeAndKeepsFractionalEdges() {
        List<PlannerRecordPiece> pieces =
                List.of(new PlannerRecordPiece(at("03:00:00"), Instant.parse("2026-10-01T03:03:37.25Z"), 1L));

        assertThat(PlannerRecordPieces.remove(pieces, at("03:01:00"), at("03:02:00")))
                .containsExactly(
                        piece("03:00:00", "03:01:00", 1L),
                        new PlannerRecordPiece(at("03:02:00"), Instant.parse("2026-10-01T03:03:37.25Z"), 1L));
    }

    @Test
    void normalizeSortsMergesTouchingSameSubjectAndKeepsEarlierOnOverlap() {
        assertThat(PlannerRecordPieces.normalize(List.of(
                        piece("03:01:00", "03:02:00", 1L),
                        piece("03:00:00", "03:01:00", 1L),
                        piece("03:01:30", "03:03:00", 2L),
                        piece("03:03:00", "03:04:00", null))))
                .containsExactly(
                        piece("03:00:00", "03:02:00", 1L),
                        piece("03:02:00", "03:03:00", 2L),
                        piece("03:03:00", "03:04:00", null));
    }

    @Test
    void spansJoinTouchingPiecesAcrossSubjectsButNotAcrossGaps() {
        assertThat(PlannerRecordPieces.spans(List.of(
                        piece("03:00:00", "03:01:00", 1L),
                        piece("03:01:00", "03:02:00", 2L),
                        piece("03:03:00", "03:04:00", 2L))))
                .containsExactly(piece("03:00:00", "03:02:00", null), piece("03:03:00", "03:04:00", null));
    }

    @Test
    void commonSubjectOnlyWhenEveryPieceShares() {
        assertThat(PlannerRecordPieces.common(
                        List.of(piece("03:00:00", "03:01:00", 1L), piece("03:02:00", "03:03:00", 1L))))
                .isEqualTo(1L);
        assertThat(PlannerRecordPieces.common(
                        List.of(piece("03:00:00", "03:01:00", 1L), piece("03:01:00", "03:02:00", null))))
                .isNull();
    }
}
