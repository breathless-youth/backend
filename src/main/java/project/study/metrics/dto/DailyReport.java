package project.study.metrics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import project.study.studysession.dto.HeavyUser;

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

    public String toSlackMessage() {
        return """
                📊 %s 지표
                • 총 가입: %d명 (어제 신규: %d명)
                • 헤비유저: %d명 — %s
                • 10분 이상 세션: %d건""".formatted(reportDate, totalUsers, newUsers, heavyUsers.size(), heavyUserList(), qualifyingSessions);
    }

    private String heavyUserList() {
        if (heavyUsers.isEmpty()) {
            return "없음";
        }
        return heavyUsers.stream()
                .map(user -> "#%d(%d일)".formatted(user.userId(), user.activeDays()))
                .collect(Collectors.joining(", "));
    }
}
