package project.study.metrics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import project.study.metrics.dto.UserSessionDetail.SessionEntry;

/**
 * 일일 지표 리포트.
 *
 * @param reportDate 집계 기준일(KST 어제) — 발송 시각이 오전 10시라 오늘은 부분 집계다
 * @param totalUsers 총 가입 수 (중복 등록 문제로 실제보다 큼 — 설계 문서의 "알려진 한계" 참고)
 * @param newUsers 기준일에 가입한 유저(가입 시각 오름차순, id·KST 시각)
 * @param heavyUsers 최근 7일 헤비유저 (인정일수 내림차순)
 * @param qualifyingSessions 기준일의 10분 이상 세션 수
 * @param roomVsSingle 기준일 10분 이상 세션의 소셜(룸) vs 싱글 순공 비교
 * @param cohort 코호트 첫주 평균 공부일수
 * @param sessionDetails 기준일 10분 이상 세션 상세(유저별 묶음, 순공 합 내림차순)
 */
public record DailyReport(
        LocalDate reportDate,
        long totalUsers,
        List<NewUser> newUsers,
        List<HeavyUser> heavyUsers,
        long qualifyingSessions,
        RoomVsSingleFocus roomVsSingle,
        CohortFirstWeek cohort,
        List<UserSessionDetail> sessionDetails) {

    // 신규·헤비유저 목록은 상한 없이 전부 보여주는 게 원칙이지만, 유저가 폭증하면 Slack text
    // 상한(40,000자)을 넘겨 발송 자체가 실패하고 그날 리포트가 통째로 날아간다. 이를 막는 failsafe로
    // 극단적 규모에서만 상위 N명으로 줄이고 나머지는 개수로 요약한다(현 규모에선 사실상 늘 전부 표시).
    private static final int MAX_LISTED = 300;
    // 세션 상세는 스펙상 상위 20명 고정
    private static final int MAX_SESSION_DETAILS_LISTED = 20;
    // Slack text 상한
    private static final int SLACK_LIMIT = 40_000;
    private static final String TRUNCATION_MARKER = "\n…(생략)";

    public String toSlackMessage() {
        String message = assemble();
        if (message.length() <= SLACK_LIMIT) {
            return message;
        }
        // 목록 상한을 다 통과해도(예: 한 유저의 세션이 수천 건) 메시지가 상한을 넘을 수 있다.
        // 마지막 안전장치로 상한 안에서 잘라 발송 자체가 실패해 그날 리포트가 통째로 날아가는 것을 막는다
        return message.substring(0, SLACK_LIMIT - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }

    private String assemble() {
        return """
                📊 %1$s 지표
                • 총 가입: %2$d명
                • 신규 가입: %3$d명%4$s
                • 헤비유저: %5$d명 — %6$s
                • 10분 이상 세션: %7$d건

                👥 소셜 vs 싱글 순공 (10분 이상 세션)
                • 소셜: %8$s
                • 싱글: %9$s

                🔁 코호트 첫주 공부일수
                • %10$s

                ⏱️ 10분 이상 세션 상세 (순공 상위 %11$d명)
                %12$s""".formatted(
                        reportDate,
                        totalUsers,
                        newUsers.size(),
                        newUserList(),
                        heavyUsers.size(),
                        heavyUserList(),
                        qualifyingSessions,
                        focusStatText(roomVsSingle.social()),
                        focusStatText(roomVsSingle.single()),
                        cohortText(),
                        MAX_SESSION_DETAILS_LISTED,
                        sessionDetailText());
    }

    private String newUserList() {
        if (newUsers.isEmpty()) {
            return "";
        }
        return " — " + truncatedList(newUsers, MAX_LISTED, user -> "#%d(%s)".formatted(user.userId(), user.joinedAt()));
    }

    private String heavyUserList() {
        if (heavyUsers.isEmpty()) {
            return "없음";
        }
        return truncatedList(heavyUsers, MAX_LISTED, user -> "#%d(%d일)".formatted(user.userId(), user.activeDays()));
    }

    private String focusStatText(FocusStat stat) {
        return "%d건(평균 %d분, 중앙 %d분)".formatted(stat.sessionCount(), stat.avgFocusMin(), stat.medianFocusMin());
    }

    private String cohortText() {
        if (cohort.cohortSize() == 0) {
            return "첫주 평균 공부일수: 집계 없음 (코호트 0명)";
        }
        // 소수점 구분자가 로케일 따라 ','가 되지 않도록 ROOT로 고정한다
        return String.format(Locale.ROOT, "첫주 평균 공부일수: %.1f일 (코호트 %d명)", cohort.avgDays(), cohort.cohortSize());
    }

    private String sessionDetailText() {
        if (sessionDetails.isEmpty()) {
            return "없음";
        }
        boolean truncated = sessionDetails.size() > MAX_SESSION_DETAILS_LISTED;
        List<UserSessionDetail> shown =
                truncated ? sessionDetails.subList(0, MAX_SESSION_DETAILS_LISTED) : sessionDetails;
        String lines = shown.stream().map(DailyReport::detailLine).collect(Collectors.joining("\n"));
        if (truncated) {
            return lines
                    + "\n(총 %d명 중 상위 %d명, 외 %d명)"
                            .formatted(
                                    sessionDetails.size(),
                                    MAX_SESSION_DETAILS_LISTED,
                                    sessionDetails.size() - MAX_SESSION_DETAILS_LISTED);
        }
        return lines;
    }

    private static String detailLine(UserSessionDetail detail) {
        String sessions =
                detail.sessions().stream().map(DailyReport::sessionText).collect(Collectors.joining(", "));
        return "#%d: %s".formatted(detail.userId(), sessions);
    }

    private static String sessionText(SessionEntry entry) {
        return "%d분(%s)".formatted(entry.focusMin(), entry.social() ? "소셜" : "싱글");
    }

    // 상위 max개만 나열하고 초과분은 "외 K명"으로 요약한다(신규·헤비유저 공통 failsafe)
    private static <T> String truncatedList(List<T> items, int max, java.util.function.Function<T, String> format) {
        boolean truncated = items.size() > max;
        List<T> shown = truncated ? items.subList(0, max) : items;
        String listed = shown.stream().map(format).collect(Collectors.joining(", "));
        return truncated ? listed + ", 외 %d명".formatted(items.size() - max) : listed;
    }
}
