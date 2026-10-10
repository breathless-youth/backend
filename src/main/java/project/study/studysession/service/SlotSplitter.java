package project.study.studysession.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.TimeSlot;

/**
 * 자정 분할 조각 하나를 시간대 구간 경계(KST 04·07·12·18·22시)로 다시 잘라 조각 순공을 구간별로 배분하는 순수 로직 (BY-828).
 * 배분은 자정 분할과 같은 규칙이다 — 이벤트를 뺀 길이 비율로 나누고 마지막 구간이 나머지를 가져가 합이 조각 순공과 같다.
 * 0초 몫은 행을 만들지 않는다.
 */
final class SlotSplitter {

    private SlotSplitter() {}

    static List<SessionSlot> split(Instant start, Instant end, int focusSec, List<StatusEvent> events) {
        if (focusSec <= 0 || !start.isBefore(end)) {
            return List.of();
        }
        List<StatusEvent> sorted = events.stream()
                .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                .toList();
        List<Instant> cuts = cuts(start, end);
        StudySessionSplitter.SegmentWeights weights = StudySessionSplitter.computeSegmentWeights(cuts, sorted);
        if (weights.totalFocusActiveSec() == 0 && weights.totalStudyActiveSec() == 0) {
            // 조각 전체가 일시정지인데 자정 배분의 나머지로 순공이 남은 극단값 — 나눌 기준이 없어 시작 구간에 둔다
            return List.of(new SessionSlot(TimeSlot.at(start), TimeSlot.slotDateOf(start), focusSec));
        }
        int count = cuts.size() - 1;
        List<SessionSlot> slots = new ArrayList<>();
        long allocated = 0;
        for (int i = 0; i < count; i++) {
            long share = i == count - 1 ? focusSec - allocated : StudySessionSplitter.focusShare(weights, i, focusSec);
            allocated += share;
            if (share > 0) {
                Instant segmentStart = cuts.get(i);
                slots.add(new SessionSlot(TimeSlot.at(segmentStart), TimeSlot.slotDateOf(segmentStart), (int) share));
            }
        }
        return slots;
    }

    private static List<Instant> cuts(Instant start, Instant end) {
        List<Instant> cuts = new ArrayList<>();
        cuts.add(start);
        for (Instant boundary = TimeSlot.nextBoundary(start);
                boundary.isBefore(end);
                boundary = TimeSlot.nextBoundary(boundary)) {
            cuts.add(boundary);
        }
        cuts.add(end);
        return cuts;
    }
}
