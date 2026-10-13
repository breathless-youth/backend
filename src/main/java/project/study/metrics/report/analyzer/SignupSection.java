package project.study.metrics.report.analyzer;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import project.study.metrics.report.Rate;

/**
 * 🕐 가입 (스펙 §4.1).
 *
 * @param minUserId 어제 가입자 중 가장 작은 회원번호, 어제 가입이 없으면 null
 * @param bands 최근 7일 가입을 3시간 구간 8개로 나눈 것(00~03 … 21~24)
 */
public record SignupSection(
        LocalDate anchor,
        int yesterdayCount,
        Long minUserId,
        Long maxUserId,
        Rate yesterdayQuick,
        int weekTotal,
        List<HourBand> bands,
        List<Share> categories,
        Attribution attribution) {

    /** 바로 공부 비율을 비교할 최소 가입 수 — 1~2명 구간의 100%가 1위로 오르지 않게. */
    public static final int MIN_SIGNUPS_FOR_RATE = 5;

    public record HourBand(int startHour, int signups, int quick) {
        public Rate quickRate() {
            return Rate.of(quick, signups);
        }
    }

    public record Share(String label, int count) {}

    /** @param missing Amplitude 속성이 없는 유저 수 — 광고·UTM 판정에서 빠진다 */
    public record Attribution(int total, int adClicks, List<Share> topUtm, int missing) {}

    public LocalDate weekFrom() {
        return anchor.minusDays(6);
    }

    /** 가입이 가장 많은 구간(같으면 이른 구간). */
    public Optional<HourBand> peak() {
        HourBand peak = null;
        for (HourBand band : bands) {
            if (band.signups() > 0 && (peak == null || band.signups() > peak.signups())) {
                peak = band;
            }
        }
        return Optional.ofNullable(peak);
    }

    /** 가입 5명 이상 구간 중 바로 공부 비율이 가장 높은 구간(같으면 가입 많은 쪽, 그다음 이른 구간). 바로 공부가 0이면 없음. */
    public Optional<HourBand> bestQuick() {
        HourBand best = null;
        for (HourBand band : bands) {
            if (band.signups() >= MIN_SIGNUPS_FOR_RATE && band.quick() > 0 && (best == null || better(band, best))) {
                best = band;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean better(HourBand candidate, HourBand best) {
        long left = (long) candidate.quick() * best.signups();
        long right = (long) best.quick() * candidate.signups();
        return left > right || (left == right && candidate.signups() > best.signups());
    }
}
