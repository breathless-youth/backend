package project.study.studysession.service;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.report.fact.SessionFact;
import project.study.studysession.repository.SessionFactQueries;

/**
 * 지표 집계 전용 서비스(매일 10시 Slack 리포트) — 앱 화면용 StudySessionService와 나눠, 지표가 늘어도 화면 서비스 파일이 커지지
 * 않게 한다. 공부한 날 판정은 리포트가 세션 단위로 한다(ADR-0012 결정 3, UserTimeline).
 */
@Service
@RequiredArgsConstructor
public class StudySessionMetricsService {

    private final SessionFactQueries sessionFactQueries;

    /** 일일 리포트 v2 — 기간(양끝 포함) 안 모든 세션을 일시정지 수·소셜 여부와 함께 (BY-904). */
    @Transactional(readOnly = true)
    public List<SessionFact> findSessionFacts(LocalDate from, LocalDate to) {
        return sessionFactQueries.findBetween(from, to);
    }
}
