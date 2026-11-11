package project.study.point.service;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 공부 포인트 숫자와 시작 날짜. 기본값(30분마다 10P, 하루 12시간)은 예시이고 기획이 정하면 프로퍼티로 바꾼다. */
@Component
public class StudyPointPolicy {

    private final int unitSec;
    private final int pointsPerUnit;
    private final int dailyCapSec;
    private final LocalDate startsOn;

    public StudyPointPolicy(
            @Value("${app.points.study.unit-sec:1800}") int unitSec,
            @Value("${app.points.study.points-per-unit:10}") int pointsPerUnit,
            @Value("${app.points.study.daily-cap-sec:43200}") int dailyCapSec,
            @Value("${app.points.study.starts-on:2026-11-11}") LocalDate startsOn) {
        this.unitSec = unitSec;
        this.pointsPerUnit = pointsPerUnit;
        this.dailyCapSec = dailyCapSec;
        this.startsOn = startsOn;
    }

    /** 이 날짜보다 앞선 공부는 포인트를 받지 않는다. 출시 전 기록을 다시 제출해 받아 가지 못하게 한다. 운영은 출시 날짜로 맞춘다. */
    public boolean counts(LocalDate statDate) {
        return !statDate.isBefore(startsOn);
    }

    /** 이 날짜보다 앞서 끝난 랭킹의 메달은 포인트를 받지 않는다. */
    public LocalDate startsOn() {
        return startsOn;
    }

    /** 하루에 공부로 받을 수 있는 최대 포인트. */
    public int maxDailyPoints() {
        return targetPoints(dailyCapSec);
    }

    public int dailyCapSec() {
        return dailyCapSec;
    }

    /** 하루 상한을 넘는 순공은 인정하지 않는다. */
    public int recognizedFocusSec(long focusSec) {
        return (int) Math.min(focusSec, dailyCapSec);
    }

    /** 인정 순공으로 그날 받아야 할 총액. 단위에 못 미치는 나머지는 버린다. */
    public int targetPoints(int recognizedFocusSec) {
        return recognizedFocusSec / unitSec * pointsPerUnit;
    }
}
