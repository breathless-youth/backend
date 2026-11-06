package project.study.metrics.amplitude;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
        // Amplitude에는 탈퇴한 회원의 이벤트가 늦게 들어오거나 남아 있다. 적재하면 탈퇴 때 지운 행이 되살아나므로 users에 있는 회원만 쓴다.
        // 잠가 두면 이 트랜잭션이 끝날 때까지 탈퇴가 그 users 행을 잡지 못하고, 탈퇴가 먼저 잡았으면 커밋을 기다린 뒤 빈 결과를 읽는다
        Set<Long> userIds = new HashSet<>(aggregate.attributes().keySet());
        aggregate.counts().keySet().forEach(key -> userIds.add(key.userId()));
        Set<Long> members = repository.lockExistingUsers(userIds);
        repository.addCounts(aggregate.counts().entrySet().stream()
                .filter(entry -> members.contains(entry.getKey().userId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        repository.upsertAttributes(aggregate.attributes().entrySet().stream()
                .filter(entry -> members.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        return true;
    }
}
