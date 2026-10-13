package project.study.room.service;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.report.fact.UserEventFact;
import project.study.room.repository.RoomMetricsQueries;

/** 지표 집계 전용 — 화면용 RoomService와 나눈다 (BY-904). */
@Service
@RequiredArgsConstructor
public class RoomMetricsService {

    private final RoomMetricsQueries roomMetricsQueries;

    @Transactional(readOnly = true)
    public List<UserEventFact> findJoins(Instant from, Instant to) {
        return roomMetricsQueries.findJoinsBetween(from, to);
    }
}
