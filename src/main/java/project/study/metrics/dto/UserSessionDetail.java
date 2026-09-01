package project.study.metrics.dto;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 10분 이상 세션 상세 — 한 유저의 그날 세션들을 순공 내림차순으로 묶는다.
 *
 * @param userId 유저 ID
 * @param sessions 그 유저의 10분 이상 세션(순공 분 내림차순)
 */
public record UserSessionDetail(long userId, List<SessionEntry> sessions) {

    /**
     * 세션들을 유저별로 묶어 상세 목록을 만든다. 유저는 그날 순공 합 내림차순(동수면 userId 오름차순),
     * 각 유저 안의 세션은 순공 분 내림차순으로 정렬한다.
     */
    public static List<UserSessionDetail> rank(List<QualifyingSession> sessions) {
        Map<Long, List<QualifyingSession>> byUser =
                sessions.stream().collect(Collectors.groupingBy(QualifyingSession::userId));

        // 정렬·랭킹은 초 단위 순공 합으로 한다 — 분으로 반올림한 뒤 더하면 경계에서 순서가 뒤바뀐다
        return byUser.entrySet().stream()
                .sorted(Comparator.comparingLong(
                                (Map.Entry<Long, List<QualifyingSession>> entry) -> totalFocusSec(entry.getValue()))
                        .reversed()
                        .thenComparingLong(Map.Entry::getKey))
                .map(entry -> new UserSessionDetail(entry.getKey(), toEntries(entry.getValue())))
                .toList();
    }

    private static long totalFocusSec(List<QualifyingSession> userSessions) {
        return userSessions.stream().mapToLong(QualifyingSession::focusSec).sum();
    }

    private static List<SessionEntry> toEntries(List<QualifyingSession> userSessions) {
        return userSessions.stream()
                .sorted(Comparator.comparingInt(QualifyingSession::focusSec).reversed())
                .map(session -> new SessionEntry(Math.round(session.focusSec() / 60.0), session.social()))
                .toList();
    }

    /**
     * 세션 한 건 표현.
     *
     * @param focusMin 순공시간(분, 반올림)
     * @param social 소셜(룸) 세션이면 true
     */
    public record SessionEntry(long focusMin, boolean social) {}
}
