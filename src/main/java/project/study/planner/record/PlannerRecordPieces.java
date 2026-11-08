package project.study.planner.record;

import static project.study.planner.record.PlannerRecordMetrics.earlier;
import static project.study.planner.record.PlannerRecordMetrics.later;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSource;
import project.study.studysession.entity.StudySessionSubjectSegment;

/** 현재 개인 기록에 실제로 남은 조각의 계산. 결과는 시작 순이고 서로 겹치지 않으며, 맞닿은 같은 과목은 합친다. */
final class PlannerRecordPieces {
    private PlannerRecordPieces() {}

    /** 원본 묶음. 세션 구간을 과목 없음으로 깔고 과목 구간을 시작 순으로 덮어 칠한다. 세션 밖 과목 구간은 버린다. */
    static List<PlannerRecordPiece> original(List<StudySession> bundle) {
        List<PlannerRecordPiece> pieces = new ArrayList<>();
        for (StudySession session : bundle) {
            Instant start = session.getStartedAt();
            Instant end = PlannerRecordViews.end(session);
            if (!start.isBefore(end)) {
                continue;
            }
            List<PlannerRecordPiece> painted = List.of(new PlannerRecordPiece(start, end, null));
            for (StudySessionSubjectSegment segment : session.getSubjectSegments().stream()
                    .sorted(Comparator.comparing(StudySessionSubjectSegment::getStartedAt))
                    .toList()) {
                painted = paint(painted, segment.getStartedAt(), segment.getEndedAt(), segment.getSubjectId());
            }
            pieces.addAll(painted);
        }
        return normalize(pieces);
    }

    /** 조각 목록이 없는 기존 수정본. 기존 조회의 과목 구간과 같은 범위를 만든다. */
    static List<PlannerRecordPiece> legacy(PlannerRecordEdit edit, List<StudySession> bundle) {
        if (edit.source() != StudySessionSource.MANUAL || !edit.preserveOriginalTotals()) {
            return List.of(new PlannerRecordPiece(edit.startedAt(), edit.endedAt(), edit.subjectId()));
        }
        // 다른 기록을 피해 저장된 옛 수동 묶음은 조각 사이 빈 구간을 기록으로 채우지 않는다.
        List<PlannerRecordPiece> pieces = new ArrayList<>();
        for (StudySession session : bundle) {
            Instant left = later(edit.startedAt(), session.getStartedAt());
            Instant right = earlier(edit.endedAt(), PlannerRecordViews.end(session));
            if (left.isBefore(right)) {
                pieces.add(new PlannerRecordPiece(left, right, edit.subjectId()));
            }
        }
        return normalize(pieces);
    }

    static List<PlannerRecordPiece> clip(List<PlannerRecordPiece> pieces, Instant from, Instant to) {
        List<PlannerRecordPiece> clipped = new ArrayList<>();
        for (PlannerRecordPiece piece : pieces) {
            Instant left = later(from, piece.startedAt());
            Instant right = earlier(to, piece.endedAt());
            if (left.isBefore(right)) {
                clipped.add(new PlannerRecordPiece(left, right, piece.subjectId()));
            }
        }
        return List.copyOf(clipped);
    }

    static boolean intersects(List<PlannerRecordPiece> pieces, Instant from, Instant to) {
        return pieces.stream()
                .anyMatch(p -> p.startedAt().isBefore(to) && p.endedAt().isAfter(from));
    }

    /** [from,to)와 겹친 부분만 subjectId로 바꾼다. 빈 시간은 만들지 않는다. */
    static List<PlannerRecordPiece> paint(List<PlannerRecordPiece> pieces, Instant from, Instant to, Long subjectId) {
        List<PlannerRecordPiece> result = new ArrayList<>();
        for (PlannerRecordPiece piece : pieces) {
            Instant left = later(from, piece.startedAt());
            Instant right = earlier(to, piece.endedAt());
            if (!left.isBefore(right)) {
                result.add(piece);
                continue;
            }
            if (piece.startedAt().isBefore(left)) {
                result.add(new PlannerRecordPiece(piece.startedAt(), left, piece.subjectId()));
            }
            result.add(new PlannerRecordPiece(left, right, subjectId));
            if (right.isBefore(piece.endedAt())) {
                result.add(new PlannerRecordPiece(right, piece.endedAt(), piece.subjectId()));
            }
        }
        return normalize(result);
    }

    /** [from,to)와 겹친 부분을 뺀다. */
    static List<PlannerRecordPiece> remove(List<PlannerRecordPiece> pieces, Instant from, Instant to) {
        List<PlannerRecordPiece> result = new ArrayList<>();
        for (PlannerRecordPiece piece : pieces) {
            if (!(piece.startedAt().isBefore(to) && piece.endedAt().isAfter(from))) {
                result.add(piece);
                continue;
            }
            if (piece.startedAt().isBefore(from)) {
                result.add(new PlannerRecordPiece(piece.startedAt(), from, piece.subjectId()));
            }
            if (to.isBefore(piece.endedAt())) {
                result.add(new PlannerRecordPiece(to, piece.endedAt(), piece.subjectId()));
            }
        }
        return normalize(result);
    }

    static List<PlannerRecordPiece> paintAll(List<PlannerRecordPiece> pieces, Long subjectId) {
        return normalize(pieces.stream()
                .map(p -> new PlannerRecordPiece(p.startedAt(), p.endedAt(), subjectId))
                .toList());
    }

    /** 과목과 무관하게 맞닿은 조각을 이은 실제 기록 구간. 최장 집중처럼 끊김만 보는 계산에 쓴다. */
    static List<PlannerRecordPiece> spans(List<PlannerRecordPiece> pieces) {
        List<PlannerRecordPiece> spans = new ArrayList<>();
        for (PlannerRecordPiece piece : normalize(pieces)) {
            if (!spans.isEmpty() && spans.getLast().endedAt().equals(piece.startedAt())) {
                spans.set(
                        spans.size() - 1, new PlannerRecordPiece(spans.getLast().startedAt(), piece.endedAt(), null));
            } else {
                spans.add(new PlannerRecordPiece(piece.startedAt(), piece.endedAt(), null));
            }
        }
        return List.copyOf(spans);
    }

    /** 모든 조각이 같은 과목이면 그 과목, 아니면 null. */
    static Long common(List<PlannerRecordPiece> pieces) {
        if (pieces.isEmpty()) {
            return null;
        }
        Long first = pieces.getFirst().subjectId();
        return pieces.stream().allMatch(p -> Objects.equals(p.subjectId(), first)) ? first : null;
    }

    /** 시작 순으로 정렬하고, 겹치면 앞 조각을 남기며, 맞닿은 같은 과목을 합친다. */
    static List<PlannerRecordPiece> normalize(List<PlannerRecordPiece> pieces) {
        List<PlannerRecordPiece> sorted = pieces.stream()
                .filter(p -> p.startedAt().isBefore(p.endedAt()))
                .sorted(Comparator.comparing(PlannerRecordPiece::startedAt))
                .toList();
        List<PlannerRecordPiece> result = new ArrayList<>();
        for (PlannerRecordPiece piece : sorted) {
            if (result.isEmpty()) {
                result.add(piece);
                continue;
            }
            PlannerRecordPiece last = result.getLast();
            Instant start = later(piece.startedAt(), last.endedAt());
            if (!start.isBefore(piece.endedAt())) {
                continue;
            }
            if (start.equals(last.endedAt()) && Objects.equals(last.subjectId(), piece.subjectId())) {
                result.set(
                        result.size() - 1, new PlannerRecordPiece(last.startedAt(), piece.endedAt(), last.subjectId()));
            } else {
                result.add(new PlannerRecordPiece(start, piece.endedAt(), piece.subjectId()));
            }
        }
        return List.copyOf(result);
    }
}
