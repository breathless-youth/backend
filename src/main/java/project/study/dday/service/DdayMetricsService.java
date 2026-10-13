package project.study.dday.service;

import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.dday.repository.DdayRepository;
import project.study.metrics.report.fact.UserEventFact;

/** 지표 집계 전용 — D-day를 만든 시각 (BY-904). 지우고 다시 만들면 마지막 생성 시각만 남는다. */
@Service
@RequiredArgsConstructor
public class DdayMetricsService {

    private final DdayRepository ddayRepository;

    @Transactional(readOnly = true)
    public List<UserEventFact> findDdaysOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return ddayRepository.findByUserIdIn(userIds).stream()
                .map(dday -> new UserEventFact(dday.getUserId(), dday.getCreatedAt()))
                .toList();
    }
}
