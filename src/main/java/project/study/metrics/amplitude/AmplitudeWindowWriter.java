package project.study.metrics.amplitude;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

/**
 * 창 하나 = 트랜잭션 하나. 처리 기록을 먼저 선점하고 이미 있으면 아무것도 쓰지 않는다 — 배포 중 태스크 둘이 같은 창을
 * 받아도, 재실행해도 한 번만 센다 (ADR-0030 결정 5). 다운로드는 이 트랜잭션 밖에서 끝낸다(커넥션을 오래 쥐지 않게).
 */
@Component
@RequiredArgsConstructor
public class AmplitudeWindowWriter {

    private final AmplitudeIngestRepository repository;

    @Transactional
    public boolean write(ExportWindow window, WindowAggregate aggregate) {
        if (!repository.claimWindow(window.date(), aggregate.eventCount(), aggregate.skipped())) {
            return false;
        }
        repository.addCounts(aggregate.counts());
        repository.upsertAttributes(aggregate.attributes());
        return true;
    }
}
