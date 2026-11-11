package project.study.point.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.point.dto.MedalPointTable;

/** 출석·하루 목표·메달 포인트 숫자. 기본값은 예시이고 기획이 정하면 프로퍼티로 바꾼다. 메달은 1·2·3위 순서로 적는다. */
@Component
public class DailyRewardPolicy {

    private static final int HALF_HOUR_SEC = 1800;

    private final int attendancePoints;
    private final int goalPointsPerHalfHour;
    private final MedalPointTable medals;

    public DailyRewardPolicy(
            @Value("${app.points.attendance.points:10}") int attendancePoints,
            @Value("${app.points.daily-goal.points-per-half-hour:1}") int goalPointsPerHalfHour,
            @Value("${app.points.medal.daily:100,50,30}") List<Integer> dailyMedal,
            @Value("${app.points.medal.weekly:300,150,100}") List<Integer> weeklyMedal,
            @Value("${app.points.medal.monthly:1000,500,300}") List<Integer> monthlyMedal) {
        this.attendancePoints = attendancePoints;
        this.goalPointsPerHalfHour = goalPointsPerHalfHour;
        this.medals = new MedalPointTable(List.copyOf(dailyMedal), List.copyOf(weeklyMedal), List.copyOf(monthlyMedal));
    }

    public int attendancePoints() {
        return attendancePoints;
    }

    /** 하루 목표 보상을 주는 목표 시간 단위(초). */
    public int goalUnitSec() {
        return HALF_HOUR_SEC;
    }

    public int goalPointsPerUnit() {
        return goalPointsPerHalfHour;
    }

    public MedalPointTable medalTable() {
        return medals;
    }

    /**
     * 하루 목표 보상은 목표 시간에 비례한다(기본 30분마다 1P = 1시간에 2P). 목표가 길수록 많이 받고, 짧게 잡으면 그만큼 적어 따로 최소 목표를 두지
     * 않는다. 공부 포인트의 하루 상한(capSec)을 넘는 목표 시간은 세지 않는다. 목표가 없거나 30분에 못 미치면 0이다.
     */
    public int goalPoints(Integer goalSec, int capSec) {
        return goalSec == null ? 0 : Math.min(goalSec, capSec) / HALF_HOUR_SEC * goalPointsPerHalfHour;
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
