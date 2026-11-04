package project.study.studysession.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import project.study.studysession.entity.StatusEvent;

/**
 * 세션 조각 하나의 순공을 KST 정시 경계로 잘라 요일(월=0) × 시각(0~23) 격자에 누적하는 순수 로직 — 공부 리듬 히트맵.
 * 배분은 SlotSplitter와 같은 규칙이다(이벤트를 뺀 길이 비율, 마지막 구간이 나머지를 가져가 합이 조각 순공과 같다).
 */
final class RhythmSplitter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private RhythmSplitter() {}

    static int[][] emptyGrid() {
        return new int[7][24];
    }

    static void accumulate(int[][] grid, Instant start, Instant end, int focusSec, List<StatusEvent> events) {
        if (focusSec <= 0 || !start.isBefore(end)) {
            return;
        }
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        List<Instant> cuts = cuts(start, end);
        StudySessionSplitter.SegmentWeights weights = StudySessionSplitter.computeSegmentWeights(cuts, sorted);
        if (weights.totalFocusActiveSec() == 0 && weights.totalStudyActiveSec() == 0) {
            add(grid, start, focusSec);
            return;
        }
        int count = cuts.size() - 1;
        long allocated = 0;
        for (int i = 0; i < count; i++) {
            long share = i == count - 1 ? focusSec - allocated : StudySessionSplitter.focusShare(weights, i, focusSec);
            allocated += share;
            add(grid, cuts.get(i), (int) share);
        }
    }

    private static void add(int[][] grid, Instant at, int focusSec) {
        ZonedDateTime kst = at.atZone(KST);
        grid[kst.getDayOfWeek().getValue() - 1][kst.getHour()] += focusSec;
    }

    private static List<Instant> cuts(Instant start, Instant end) {
        List<Instant> cuts = new ArrayList<>();
        cuts.add(start);
        for (Instant boundary = start.atZone(KST)
                        .truncatedTo(ChronoUnit.HOURS)
                        .plusHours(1)
                        .toInstant();
                boundary.isBefore(end);
                boundary = boundary.plus(1, ChronoUnit.HOURS)) {
            cuts.add(boundary);
        }
        cuts.add(end);
        return cuts;
    }
}
