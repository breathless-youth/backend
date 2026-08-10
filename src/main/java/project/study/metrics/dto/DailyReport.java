package project.study.metrics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 일일 지표 리포트.
 *
 * @param reportDate 집계 기준일(KST 어제) — 발송 시각이 오전 10시라 오늘은 부분 집계다
 * @param totalUsers 총 가입 수 (중복 등록 문제로 실제보다 큼 — 설계 문서의 "알려진 한계" 참고)
 * @param newUsers 기준일에 가입한 수
 * @param heavyUsers 최근 7일 헤비유저 (인정일수 내림차순)
 * @param qualifyingSessions 기준일의 10분 이상 세션 수
 */
public record DailyReport(
        LocalDate reportDate, long totalUsers, long newUsers, List<HeavyUser> heavyUsers, long qualifyingSessions) {

    // Slack text 상한(40,000자)에 걸리지 않도록 헤비유저 목록에 상한을 둔다. 유저가 늘어 헤비유저가
    // 수천 명이 되면 발송이 실패하는데, 그때는 이미 날짜가 선점된 뒤라 그날 리포트가 통째로
    // 날아간다 — 그런 실패를 만들지 않기 위해 상위 N명만 나열하고 나머지는 개수로 요약한다.
    private static final int MAX_HEAVY_USERS_LISTED = 20;

    public String toSlackMessage() {
        return """
                📊 %1$s 지표
                • 총 가입: %2$d명 (%1$s 신규: %3$d명)
                • 헤비유저: %4$d명 — %5$s
                • 10분 이상 세션: %6$d건""".formatted(reportDate, totalUsers, newUsers, heavyUsers.size(), heavyUserList(), qualifyingSessions);
    }

    private String heavyUserList() {
        if (heavyUsers.isEmpty()) {
            return "없음";
        }
        boolean truncated = heavyUsers.size() > MAX_HEAVY_USERS_LISTED;
        List<HeavyUser> shown = truncated ? heavyUsers.subList(0, MAX_HEAVY_USERS_LISTED) : heavyUsers;
        String listed = shown.stream()
                .map(user -> "#%d(%d일)".formatted(user.userId(), user.activeDays()))
                .collect(Collectors.joining(", "));

        return truncated ? listed + ", 외 %d명".formatted(heavyUsers.size() - MAX_HEAVY_USERS_LISTED) : listed;
    }
}
