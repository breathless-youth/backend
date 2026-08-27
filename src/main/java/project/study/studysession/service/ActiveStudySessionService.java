package project.study.studysession.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.NotFoundException;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.entity.ActiveStudySession;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.repository.ActiveStudySessionRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 진행중 세션 스냅샷(draft) 관리 — 하트비트 UPSERT와 무응답 draft 자동 확정 (BY-447). */
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

    /** 이 시간 넘게 하트비트가 없으면 죽었다고 본다 — 30초 주기 기준 10회 연속 유실. 판정은 서버 시계(lastSeenAt). */
    static final Duration FINALIZE_GRACE = Duration.ofMinutes(5);

    @Transactional(readOnly = true)
    public List<Long> findStaleDraftIds() {
        return activeStudySessionRepository
                .findByLastSeenAtBefore(clock.instant().minus(FINALIZE_GRACE))
                .stream()
                .map(ActiveStudySession::getId)
                .toList();
    }

    /**
     * draft를 세션으로 확정한다 — 검증·자정 분할·대체 정책은 전부 create(autoFinalized=true) 재사용.
     * 일부러 트랜잭션을 걸지 않는다: create가 자체 트랜잭션으로 돌아야, 유니크 충돌로 create가
     * 롤백돼도(rollback-only 오염) 후속 draft 정리가 새 트랜잭션에서 살아남는다 — 컨트롤러가
     * DuplicateSessionException 후 findExistingSubmission을 새 트랜잭션으로 부르는 것과 같은 이유다.
     * 세션 저장과 draft 삭제의 원자성은 create 안에서 보장된다(성공 시 create가 draft도 지운다).
     */
    public void finalizeDraft(Long draftId) {
        ActiveStudySession draft =
                activeStudySessionRepository.findById(draftId).orElse(null);
        if (draft == null) {
            return; // 최종 제출이 먼저 처리해 이미 삭제됨
        }
        List<StatusEventRequest> events =
                objectMapper.readValue(draft.getEvents(), new TypeReference<List<StatusEventRequest>>() {});
        StudySessionCreateRequest request = new StudySessionCreateRequest(
                draft.getUserId(),
                draft.getStartedAt(),
                draft.getReportedAt(),
                draft.getStudySec(),
                draft.getFocusSec(),
                events);
        try {
            studySessionService.create(draft.getUserId(), request, true);
        } catch (DuplicateSessionException e) {
            // 별개 제출의 분할 조각과 시각이 충돌 — 기록이 이미 있으니 아래에서 draft만 정리한다
        }
        // create 성공 경로는 이미 트랜잭션 안에서 draft를 지웠으므로 no-op이고,
        // 멱등 반환(클라 제출본 존재)·중복 충돌 경로에서만 실제로 지운다 (deleteById는 없으면 무시)
        activeStudySessionRepository.deleteById(draftId);
    }

    /**
     * 확정이 불가능한 draft를 폐기한다 — finalizeDraft의 create가 검증 예외로 실패한 뒤 스케줄러가
     * 호출한다. 하트비트 검증이 막았어야 할 데이터라 재시도해도 영원히 실패한다.
     */
    @Transactional
    public void discardDraft(Long draftId) {
        activeStudySessionRepository.deleteById(draftId);
    }
}
