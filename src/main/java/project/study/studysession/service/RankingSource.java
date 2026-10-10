package project.study.studysession.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.studysession.dto.RankingDaysRow;
import project.study.studysession.dto.RankingStreakRow;
import project.study.studysession.dto.RankingTotalRow;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.StudySessionRankingQueries;

/**
 * 랭킹(BY-828)이 세션 데이터를 읽는 유일한 입구 — 집계 쿼리·진행 중 조각·스트릭을 한곳에 모아 ranking 도메인이 세션 내부
 * 구조(리포지토리·분할기)를 직접 알지 않게 한다. metrics의 StudySessionMetricsService와 같은 자리다.
 */
@Service
@RequiredArgsConstructor
public class RankingSource {

    private final StudySessionRankingQueries queries;

    public List<RankingTotalRow> periodTotals(LocalDate from, LocalDate to, Long userId) {
        return queries.periodTotals(from, to, userId);
    }

    public List<RankingTotalRow> slotTotals(TimeSlot slot, LocalDate from, LocalDate to, Long userId) {
        return queries.slotTotals(slot, from, to, userId);
    }

    public List<RankingDaysRow> studyDays(LocalDate today, Long userId) {
        return queries.studyDays(today, userId);
    }

    public List<RankingStreakRow> maxStreaks(LocalDate today, Long userId) {
        return queries.maxStreaks(today, userId);
    }

    public Map<Long, String> activeNicknames(Collection<Long> userIds) {
        return queries.activeNicknames(userIds);
    }
}
