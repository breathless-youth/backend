package project.study.metrics.dto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 일일 지표 리포트.
 *
 * @param reportDate 집계 기준일(KST 어제) — 발송 시각이 오전 10시라 오늘은 부분 집계다
 * @param totalUsers 총 가입 수 (중복 등록 문제로 실제보다 큼 — 설계 문서의 "알려진 한계" 참고)
 * @param newUsers 기준일에 가입한 유저들 (가입 시각 오름차순)
 * @param heavyUsers 최근 7일 헤비유저 (인정일수 내림차순)
 * @param qualifyingSessions 기준일의 10분 이상 세션 수
 */
public record DailyReport(
        LocalDate reportDate,
        long totalUsers,
        List<NewUser> newUsers,
        List<HeavyUser> heavyUsers,
        long qualifyingSessions) {

    // Slack text 상한(40,000자)에 걸리지 않도록 유저 목록에 상한을 둔다. 유저가 늘어 목록이
    // 수천 명이 되면 발송 자체가 실패하므로, 상위 N명만 나열하고 나머지는 개수로 요약한다.
    private static final int MAX_HEAVY_USERS_LISTED = 20;
    private static final int MAX_NEW_USERS_LISTED = 20;

    // 가입 시각 표기 — 기준일이 제목에 있으므로 시:분만. 저장은 UTC Instant라 KST로 변환해 표시
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public String toSlackMessage() {
        return """
                📊 %1$s 지표
                • 총 가입: %2$d명
                • 신규 가입: %3$d명%4$s
                • 헤비유저: %5$d명 — %6$s
                • 10분 이상 세션: %7$d건""".formatted(
                        reportDate,
                        totalUsers,
                        newUsers.size(),
                        newUserList(),
                        heavyUsers.size(),
                        heavyUserList(),
                        qualifyingSessions);
    }

    private String newUserList() {
        if (newUsers.isEmpty()) {
            return "";
        }
        boolean truncated = newUsers.size() > MAX_NEW_USERS_LISTED;
        List<NewUser> shown = truncated ? newUsers.subList(0, MAX_NEW_USERS_LISTED) : newUsers;
        String listed = shown.stream()
                .map(user -> "#%d(%s)".formatted(user.userId(), TIME_FORMAT.format(user.registeredAt())))
                .collect(Collectors.joining(", "));

        String suffix = truncated ? ", 외 %d명".formatted(newUsers.size() - MAX_NEW_USERS_LISTED) : "";
        return " — " + listed + suffix;
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
