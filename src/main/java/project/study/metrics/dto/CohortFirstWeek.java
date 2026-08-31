package project.study.metrics.dto;

import java.util.List;

/**
 * 코호트 첫주 공부일수 지표 — 유저 생애 첫 10분 이상 공부일(D0)의 7일 창 `[D0, D0+6]`이 완결된
 * 유저들만 코호트로 잡고, 각자 그 창 안에서 10분 이상 공부한 날 수의 평균을 낸다.
 *
 * @param cohortSize 코호트 유저 수
 * @param avgDays 첫주 공부일수의 평균(코호트가 비면 0)
 */
public record CohortFirstWeek(long cohortSize, double avgDays) {

    /**
     * @param perUserDays 코호트 각 유저의 첫주 공부일수(1~7)
     */
    public static CohortFirstWeek from(List<Long> perUserDays) {
        if (perUserDays.isEmpty()) {
            return new CohortFirstWeek(0, 0);
        }
        double average =
                perUserDays.stream().mapToLong(Long::longValue).average().orElse(0);
        return new CohortFirstWeek(perUserDays.size(), average);
    }
}
