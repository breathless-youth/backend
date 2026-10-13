package project.study.subject.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.subject.repository.SubjectMetricsQueries;

/** 지표 집계 전용 — 화면용 StudySubjectService와 나눈다 (BY-904). */
@Service
@RequiredArgsConstructor
public class SubjectMetricsService {

    private final SubjectMetricsQueries subjectMetricsQueries;

    @Transactional(readOnly = true)
    public List<SubjectFact> findSubjectsOf(Collection<Long> userIds) {
        return subjectMetricsQueries.findSubjectsOf(userIds);
    }

    @Transactional(readOnly = true)
    public List<UserEventFact> findTasksDone(Instant from, Instant to) {
        return subjectMetricsQueries.findTasksDoneBetween(from, to);
    }
}
