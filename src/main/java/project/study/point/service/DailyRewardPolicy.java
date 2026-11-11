package project.study.point.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 출석·하루 목표·메달 포인트 숫자. 기본값은 예시이고 기획이 정하면 프로퍼티로 바꾼다. 메달은 1·2·3위 순서로 적는다. */
@Component
public class DailyRewardPolicy {

    private final int attendancePoints;
    private final int dailyGoalPoints;
    private final int minGoalSec;
    private final List<Integer> dailyMedal;
    private final List<Integer> weeklyMedal;
    private final List<Integer> monthlyMedal;

    public DailyRewardPolicy(
            @Value("${app.points.attendance.points:10}") int attendancePoints,
            @Value("${app.points.daily-goal.points:50}") int dailyGoalPoints,
            @Value("${app.points.daily-goal.min-goal-sec:3600}") int minGoalSec,
            @Value("${app.points.medal.daily:100,50,30}") List<Integer> dailyMedal,
            @Value("${app.points.medal.weekly:300,150,100}") List<Integer> weeklyMedal,
            @Value("${app.points.medal.monthly:1000,500,300}") List<Integer> monthlyMedal) {
        this.attendancePoints = attendancePoints;
        this.dailyGoalPoints = dailyGoalPoints;
        this.minGoalSec = minGoalSec;
        this.dailyMedal = List.copyOf(dailyMedal);
        this.weeklyMedal = List.copyOf(weeklyMedal);
        this.monthlyMedal = List.copyOf(monthlyMedal);
    }

    public int attendancePoints() {
        return attendancePoints;
    }

    public int dailyGoalPoints() {
        return dailyGoalPoints;
    }

    /** 목표를 아주 짧게 잡고 매일 받아 가지 못하게 두는 최소 목표. */
    public int minGoalSec() {
        return minGoalSec;
    }

    /** 목표가 최소 이상이고 측정 순공이 목표 이상이면 받을 수 있다. */
    public boolean goalAchieved(Integer goalSec, long focusSec) {
        return goalSec != null && goalSec >= minGoalSec && focusSec >= goalSec;
    }

    /** period는 랭킹판 키의 둘째 마디(DAILY·WEEKLY·MONTHLY), rank는 1~3. 모르는 기간이나 순위는 0이다. */
    public int medalPoints(String period, int rank) {
        List<Integer> table =
                switch (period) {
                    case "DAILY" -> dailyMedal;
                    case "WEEKLY" -> weeklyMedal;
                    case "MONTHLY" -> monthlyMedal;
                    default -> List.of();
                };
        return rank >= 1 && rank <= table.size() ? table.get(rank - 1) : 0;
    }
}
