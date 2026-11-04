package project.study.studysession.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.studysession.dto.NewDraft;
import project.study.studysession.repository.NewDraftQueries;

/**
 * 그룹(BY-1124)이 "누가 방금 공부를 시작했나"를 읽는 입구 — {@link MemberStudySource}와 같은 자리다. group 도메인이 draft 테이블이나 리포지토리를
 * 직접 알지 않게 한다. 체크포인트 요청 경로는 건드리지 않고, 일정 간격으로 도는 쪽이 새 draft를 꺼내 간다.
 */
@Service
@RequiredArgsConstructor
public class NewDraftSource {

    /**
     * 첫 체크포인트는 시작 약 30초 뒤에 오므로 시작한 지 이보다 오래된 draft는 지금 시작한 공부가 아니다 — 앱이 오래 오프라인이다가 한꺼번에
     * 올린 스냅샷 같은 것이다.
     */
    static final Duration FRESH_WINDOW = Duration.ofMinutes(10);

    private final NewDraftQueries queries;

    /**
     * 새로 생긴 draft를 꺼낸다. 각 draft는 한 번만, 한 태스크에만 나온다. 시작한 지 10분이 넘은 draft와 이미 확정된 세션의 draft는 표시만 하고
     * 돌려주지 않는다.
     */
    @Transactional
    public List<NewDraft> claimNewDrafts(Instant now) {
        return queries.claim(now.minus(FRESH_WINDOW));
    }
}
