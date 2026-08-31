package project.study.metrics.dto;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 한 무리(소셜 또는 싱글) 세션의 순공시간 요약 — 건수·평균(분)·중앙값(분).
 * 평균/중앙값은 초 단위로 계산한 뒤 분으로 반올림한다.
 *
 * @param sessionCount 세션 수
 * @param avgFocusMin 평균 순공시간(분, 반올림)
 * @param medianFocusMin 중앙값 순공시간(분, 반올림)
 */
public record FocusStat(long sessionCount, long avgFocusMin, long medianFocusMin) {

    public static FocusStat of(List<QualifyingSession> sessions) {
        if (sessions.isEmpty()) {
            return new FocusStat(0, 0, 0);
        }
        int[] sorted =
                sessions.stream().mapToInt(QualifyingSession::focusSec).sorted().toArray();
        double mean = IntStream.of(sorted).average().orElse(0);
        return new FocusStat(sorted.length, toMinutes(mean), toMinutes(medianSec(sorted)));
    }

    private static double medianSec(int[] sorted) {
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    private static long toMinutes(double seconds) {
        return Math.round(seconds / 60.0);
    }
}
