package project.study.studysession.service;

import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import project.study.common.NotFoundException;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.repository.ActiveStudySessionRepository;
import tools.jackson.databind.ObjectMapper;

/** 진행중 세션 스냅샷(draft) 관리 — 하트비트 UPSERT (BY-447). 확정은 스케줄러 태스크에서 추가한다. */
@Service
@RequiredArgsConstructor
public class ActiveStudySessionService {

    // V12가 이름 없이 만든 FK의 PostgreSQL 자동 명명 규칙 이름
    private static final String USER_FK_CONSTRAINT = "active_study_session_user_id_fkey";

    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final StudySessionService studySessionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 누적 스냅샷을 draft에 UPSERT한다. 검증은 확정 시 실행될 createSessions를 그대로 호출하고
     * 결과를 버리는 방식으로 재사용한다 — draft가 항상 확정 가능한 상태임을 같은 코드 경로로 보장한다.
     * 동시 INSERT 레이스와 역순 도착은 네이티브 UPSERT가 원자적으로 걸러 조용히 무시된다(0행 갱신).
     */
    public void reportSnapshot(ActiveSessionSnapshotRequest request) {
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        studySessionService.createSessions(
                request.userId(),
                request.startedAt(),
                request.reportedAt(),
                request.studySec(),
                request.focusSec(),
                events);

        String eventsJson = objectMapper.writeValueAsString(request.events());
        try {
            activeStudySessionRepository.upsertSnapshot(
                    request.userId(),
                    request.startedAt(),
                    request.reportedAt(),
                    clock.instant(),
                    request.studySec(),
                    request.focusSec(),
                    eventsJson);
        } catch (DataIntegrityViolationException e) {
            String constraint = StudySessionService.violatedConstraint(e);
            if (USER_FK_CONSTRAINT.equalsIgnoreCase(constraint)) {
                throw new NotFoundException("존재하지 않는 사용자입니다: " + request.userId());
            }
            throw e;
        }
    }
}
