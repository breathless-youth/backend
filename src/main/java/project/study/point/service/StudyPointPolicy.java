package project.study.point.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.point.dto.StudyPointTier;

/**
 * 공부 포인트 숫자와 시작 날짜 (BY-1298). 하루 순공을 구간으로 나눠 구간마다 30분에 주는 포인트를 다르게 줄 수 있고, 방에 나 말고 한 명
 * 이상 있던 시간(같이)은 혼자보다 많이 준다. 기본값은 구간 하나로 하루 공부 시간과 상관없이 30분마다 혼자 7P, 같이 10P, 하루 10시간까지다
 * (BY-1316). 프로퍼티로 구간을 다시 나눌 수 있다. 마지막 구간의 끝이 하루 상한이다.
 */
@Component
public class StudyPointPolicy {

    private static final double ROUNDING_GUARD = 1e-6;

    private final int unitSec;
    private final List<StudyPointTier> tiers;
    private final LocalDate startsOn;

    public StudyPointPolicy(
            @Value("${app.points.study.unit-sec:1800}") int unitSec,
            @Value("${app.points.study.tier-ends-sec:36000}") List<Integer> tierEndsSec,
            @Value("${app.points.study.solo-points:7}") List<Integer> soloPoints,
            @Value("${app.points.study.together-points:10}") List<Integer> togetherPoints,
            @Value("${app.points.study.starts-on:2026-11-11}") LocalDate startsOn) {
        if (tierEndsSec.isEmpty()
                || tierEndsSec.size() != soloPoints.size()
                || tierEndsSec.size() != togetherPoints.size()) {
            throw new IllegalArgumentException("공부 포인트 구간의 끝·혼자·같이 값은 개수가 같아야 한다");
        }
        this.unitSec = unitSec;
        List<StudyPointTier> built = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < tierEndsSec.size(); i++) {
            int to = tierEndsSec.get(i);
            if (to <= from || to % unitSec != 0) {
                throw new IllegalArgumentException("공부 포인트 구간의 끝은 커져야 하고 단위의 배수여야 한다: " + tierEndsSec);
            }
            built.add(new StudyPointTier(from, to, soloPoints.get(i), togetherPoints.get(i)));
            from = to;
        }
        this.tiers = List.copyOf(built);
        this.startsOn = startsOn;
    }

    /** 하루 가운데 공부한 한 조각. 순공과 그 가운데 같이 한 순공(초). 시간순으로 넘긴다. */
    public record Piece(int focusSec, int togetherSec) {}

    /** 이 날짜보다 앞선 공부는 포인트를 받지 않는다. 출시 전 기록을 다시 제출해 받아 가지 못하게 한다. 운영은 출시 날짜로 맞춘다. */
    public boolean counts(LocalDate statDate) {
        return !statDate.isBefore(startsOn);
    }

    /** 이 날짜보다 앞서 끝난 랭킹의 메달은 포인트를 받지 않는다. */
    public LocalDate startsOn() {
        return startsOn;
    }

    public int unitSec() {
        return unitSec;
    }

    public List<StudyPointTier> tiers() {
        return tiers;
    }

    /** 구간을 모르는 예전 앱이 쓰는 값. 첫 구간의 혼자 값이다. */
    public int pointsPerUnit() {
        return tiers.getFirst().soloPoints();
    }

    /** 하루에 공부로 받을 수 있는 최대 포인트(상한까지 모두 같이 한 경우). */
    public int maxDailyPoints() {
        return targetPoints(List.of(new Piece(dailyCapSec(), dailyCapSec())));
    }

    public int dailyCapSec() {
        return tiers.getLast().toSec();
    }

    /** 하루 상한을 넘는 순공은 인정하지 않는다. */
    public int recognizedFocusSec(long focusSec) {
        return (int) Math.min(focusSec, dailyCapSec());
    }

    /** 모두 혼자 한 순공으로 받아야 할 총액. */
    public int targetPoints(int soloFocusSec) {
        return targetPoints(List.of(new Piece(soloFocusSec, 0)));
    }

    /**
     * 그날 받아야 할 총액. 조각을 시간순으로 쌓아 가며 구간 값을 매기고, 한 조각 안에서는 같이 한 시간이 고르게 퍼져 있다고 본다. 상한을 넘거나
     * 단위(30분)를 못 채운 끝자락은 버린다. 한 단위 안에 혼자와 같이가 섞이면 시간 비율대로 주고 마지막에 한 번만 내림한다.
     */
    public int targetPoints(List<Piece> pieces) {
        long total = pieces.stream().mapToLong(Piece::focusSec).sum();
        long countable = Math.min(total, dailyCapSec()) / unitSec * unitSec;
        double weighted = 0;
        long position = 0;
        for (Piece piece : pieces) {
            long focusLeft = piece.focusSec();
            double togetherRatio = focusLeft <= 0 ? 0 : (double) Math.min(piece.togetherSec(), focusLeft) / focusLeft;
            while (focusLeft > 0 && position < countable) {
                StudyPointTier tier = tierAt(position);
                long chunk = Math.min(focusLeft, Math.min(tier.toSec(), countable) - position);
                weighted += chunk * (togetherRatio * tier.togetherPoints() + (1 - togetherRatio) * tier.soloPoints());
                position += chunk;
                focusLeft -= chunk;
            }
        }
        // 실수 오차로 정수 바로 아래에 걸린 값을 깎지 않는다
        return (int) Math.floor(weighted / unitSec + ROUNDING_GUARD);
    }

    private StudyPointTier tierAt(long position) {
        for (StudyPointTier tier : tiers) {
            if (position < tier.toSec()) {
                return tier;
            }
        }
        return tiers.getLast();
    }
}
