package project.study.point.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.point.dto.GoalPointTier;
import project.study.point.dto.MedalPointTable;

/** 출석·하루 목표·메달 포인트 숫자. 기본값은 2026-11-15에 정한 값이고(BY-1298) 프로퍼티로 바꾼다. 메달은 1·2·3위 순서로 적는다. */
@Component
public class DailyRewardPolicy {

    private static final int HALF_HOUR_SEC = 1800;

    private final int attendancePoints;
    private final List<GoalPointTier> goalTiers;
    private final MedalPointTable medals;

    public DailyRewardPolicy(
            @Value("${app.points.attendance.points:15}") int attendancePoints,
            @Value("${app.points.daily-goal.tier-ends-sec:7200,28800}") List<Integer> goalTierEndsSec,
            @Value("${app.points.daily-goal.tier-points:20,5}") List<Integer> goalTierPoints,
            @Value("${app.points.medal.daily:5,3,1}") List<Integer> dailyMedal,
            @Value("${app.points.medal.weekly:10,5,3}") List<Integer> weeklyMedal,
            @Value("${app.points.medal.monthly:50,30,10}") List<Integer> monthlyMedal) {
        this.attendancePoints = attendancePoints;
        this.goalTiers = goalTiers(goalTierEndsSec, goalTierPoints);
        this.medals = new MedalPointTable(List.copyOf(dailyMedal), List.copyOf(weeklyMedal), List.copyOf(monthlyMedal));
    }

    private static List<GoalPointTier> goalTiers(List<Integer> endsSec, List<Integer> points) {
        if (endsSec.isEmpty() || endsSec.size() != points.size()) {
            throw new IllegalArgumentException("하루 목표 보상 구간의 끝과 포인트는 개수가 같아야 한다");
        }
        List<GoalPointTier> tiers = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < endsSec.size(); i++) {
            int to = endsSec.get(i);
            if (to <= from || to % HALF_HOUR_SEC != 0) {
                throw new IllegalArgumentException("하루 목표 보상 구간의 끝은 커져야 하고 30분의 배수여야 한다: " + endsSec);
            }
            tiers.add(new GoalPointTier(from, to, points.get(i)));
            from = to;
        }
        return List.copyOf(tiers);
    }

    public int attendancePoints() {
        return attendancePoints;
    }

    /** 하루 목표 보상을 주는 목표 시간 단위(초). */
    public int goalUnitSec() {
        return HALF_HOUR_SEC;
    }

    /** 구간을 모르는 예전 앱이 쓰는 값. 첫 구간의 값이다. */
    public int goalPointsPerUnit() {
        return goalTiers.getFirst().points();
    }

    public List<GoalPointTier> goalTiers() {
        return goalTiers;
    }

    /** 이보다 긴 목표 시간은 세지 않는다. */
    public int goalCapSec() {
        return goalTiers.getLast().toSec();
    }

    public MedalPointTable medalTable() {
        return medals;
    }

    /**
     * 하루 목표 보상은 목표 시간에 비례한다(BY-1298, 기본 30분마다 처음 2시간은 20P, 그 뒤 8시간까지 5P). 목표가 길수록 많이 받고 짧게 잡으면
     * 그만큼 적어 따로 최소 목표를 두지 않는다. 목표가 없거나 30분에 못 미치면 0이다.
     */
    public int goalPoints(Integer goalSec) {
        if (goalSec == null) {
            return 0;
        }
        int points = 0;
        for (GoalPointTier tier : goalTiers) {
            int counted = Math.min(goalSec, tier.toSec()) / HALF_HOUR_SEC * HALF_HOUR_SEC - tier.fromSec();
            if (counted > 0) {
                points += counted / HALF_HOUR_SEC * tier.points();
            }
        }
        return points;
    }

    /** period는 랭킹판 키의 둘째 마디(DAILY·WEEKLY·MONTHLY), rank는 1~3. 모르는 기간이나 순위는 0이다. */
    public int medalPoints(String period, int rank) {
        List<Integer> table =
                switch (period) {
                    case "DAILY" -> medals.daily();
                    case "WEEKLY" -> medals.weekly();
                    case "MONTHLY" -> medals.monthly();
                    default -> List.of();
                };
        return rank >= 1 && rank <= table.size() ? table.get(rank - 1) : 0;
    }
}
