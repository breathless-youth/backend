package project.study.metrics.amplitude;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;
import project.study.metrics.amplitude.repository.AmplitudeQueryRepository;

/** 일일 리포트가 읽는 Amplitude 횟수·속성·수집일 (스펙 §7.5, BY-904). */
@Service
@RequiredArgsConstructor
public class AmplitudeQueryService {

    private final AmplitudeQueryRepository queryRepository;
    private final AmplitudeIngestRepository ingestRepository;

    @Transactional(readOnly = true)
    public List<AmplitudeDailyCount> findCounts(LocalDate from, LocalDate to, Collection<String> keys) {
        return queryRepository.findCounts(from, to, keys);
    }

    @Transactional(readOnly = true)
    public Map<Long, AmplitudeAttribute> findAttributes(Collection<Long> userIds) {
        return queryRepository.findAttributes(userIds);
    }

    /** 창 처리 기록이 있는 KST 날짜 — 이 날의 Amplitude 값만 믿는다. */
    @Transactional(readOnly = true)
    public Set<LocalDate> collectedDates(LocalDate from, LocalDate to) {
        return ingestRepository.findProcessedDates(from, to);
    }
}
