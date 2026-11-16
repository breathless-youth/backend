package project.study.point.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.point.repository.PointRepository;
import project.study.point.repository.PointRepository.Interval;
import project.study.point.repository.PointRepository.MeasuredPiece;
import project.study.point.service.StudyPointPolicy.Piece;

/**
 * 그날 측정한 공부 조각과 그 가운데 같이 한 순공 (BY-1298). "같이"는 방(초대코드 방·그룹 공부방)에 나 말고 한 명 이상이 자리를 갖고 있던
 * 시간이다. 서버는 상대가 그 순간 집중 중이었는지의 이력은 갖고 있지 않아, 자리에 있었는지로 본다. 조각의 순공은 조각 길이 가운데 같이 있던
 * 시간의 비율만큼 같이 한 것으로 친다(쉬는 시간이 어디였는지는 모른다).
 */
@Component
@RequiredArgsConstructor
public class StudyPieces {

    private final PointRepository repository;
    private final Clock clock;

    /** 시간순. 측정 조각이 없으면 빈 목록. */
    public List<Piece> on(long userId, LocalDate date) {
        List<MeasuredPiece> measured = repository.measuredPiecesOn(userId, date);
        if (measured.isEmpty()) {
            return List.of();
        }
        Instant from = measured.getFirst().startedAt();
        Instant to = measured.stream()
                .map(MeasuredPiece::endedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        List<Interval> together = merge(repository.togetherIntervals(userId, from, to, clock.instant()));
        List<Piece> pieces = new ArrayList<>();
        for (MeasuredPiece piece : measured) {
            long length = Duration.between(piece.startedAt(), piece.endedAt()).toSeconds();
            long overlap = Math.min(length, overlapSec(piece, together));
            int togetherSec = length <= 0 ? 0 : (int) (piece.focusSec() * overlap / length);
            pieces.add(new Piece(piece.focusSec(), togetherSec));
        }
        return pieces;
    }

    /** 겹치는 구간을 합친다. 방에 다른 사람이 여럿이어도 시간은 한 번만 센다. */
    private static List<Interval> merge(List<Interval> intervals) {
        List<Interval> sorted = intervals.stream()
                .filter(interval -> interval.end().isAfter(interval.start()))
                .sorted(Comparator.comparing(Interval::start))
                .toList();
        List<Interval> merged = new ArrayList<>();
        for (Interval next : sorted) {
            if (!merged.isEmpty() && !next.start().isAfter(merged.getLast().end())) {
                Interval last = merged.removeLast();
                merged.add(new Interval(last.start(), last.end().isAfter(next.end()) ? last.end() : next.end()));
            } else {
                merged.add(next);
            }
        }
        return merged;
    }

    private static long overlapSec(MeasuredPiece piece, List<Interval> together) {
        long total = 0;
        for (Interval interval : together) {
            Instant start = interval.start().isAfter(piece.startedAt()) ? interval.start() : piece.startedAt();
            Instant end = interval.end().isBefore(piece.endedAt()) ? interval.end() : piece.endedAt();
            if (end.isAfter(start)) {
                total += Duration.between(start, end).toSeconds();
            }
        }
        return total;
    }
}
