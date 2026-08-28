package project.study.studysession.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.NotFoundException;
import project.study.studysession.dto.SessionRecoveryResponse;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/**
 * 비정상 종료 후 홈 재진입 시 복구 판별·확인 (BY-455). 이어하기용 복구 조회(GET /active)와 달리,
 * 이어하지 않고 자동 저장된 마지막 세션을 확인만 하는 경로다.
 *
 * <p>일부러 클래스 트랜잭션을 걸지 않는다: draft 확정은 {@link ActiveStudySessionService#finalizeDraft}가
 * 자체 트랜잭션(내부 create)으로 돌아야, 유니크 충돌로 롤백돼도 후속 확인 처리가 오염되지 않는다.
 * 확인 시각 기록은 {@link StudySessionRepository#acknowledgeRecovery} 한 문장이 자체 트랜잭션으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class SessionRecoveryService {

    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final ActiveStudySessionService activeStudySessionService;
    private final StudySessionRepository studySessionRepository;
    private final Clock clock;

    public SessionRecoveryResponse recover(Long userId) {
        // 1. 아직 정리되지 않은 draft가 있으면 지금 확정한다(늦은 하트비트로 되살아난 잔여 draft도 여기서 정리된다).
        //    확정 결과가 자동 확정본일 때만 복구 대상이다 — 정상 제출본의 잔여 draft는 확정만 되고 404다.
        //    알려진 한계: 이미 확인된 자동 확정본에 지연 하트비트가 도착해 draft가 되살아나면, finalize의
        //    auto→auto 대체(ADR-0014)가 새 rows의 확인 시각을 null로 초기화해 복구 카드가 1회 더 노출될 수 있다.
        //    데이터 손실은 없고 발생 조건도 드물어(자동 확정 이후 지연 하트비트), 대체 정책과의 충돌을 피하려 감수한다.
        var draft = activeStudySessionRepository.findFirstByUserIdOrderByLastSeenAtDesc(userId);
        if (draft.isPresent()) {
            Instant submissionStartedAt = draft.get().getStartedAt();
            activeStudySessionService.finalizeDraft(draft.get().getId());
            return acknowledgeAndSummarize(userId, submissionStartedAt);
        }
        // 2. draft가 없으면 가장 최근 세션의 제출을 기준으로 판별한다
        StudySession recent = studySessionRepository
                .findFirstByUserIdOrderByStartedAtDesc(userId)
                .orElseThrow(() -> new NotFoundException("복구할 세션이 없습니다"));
        return acknowledgeAndSummarize(userId, recent.getSubmissionStartedAt());
    }

    /**
     * 한 제출(submissionStartedAt)의 조각들이 미확인 자동 확정본이면 확인 처리하고 한 건으로 집계해 반환한다.
     * 정상 종료 세션(autoFinalized=false)·이미 확인된 세션·기록 없음은 모두 404.
     * 확인 시각 기록은 IS NULL 조건부라, 동시 복구 요청 중 실제로 갱신한 요청만 세션을 노출한다.
     */
    private SessionRecoveryResponse acknowledgeAndSummarize(Long userId, Instant submissionStartedAt) {
        List<StudySession> group = studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(
                userId, submissionStartedAt);
        if (group.isEmpty() || !isUnacknowledgedAutoFinalized(group)) {
            throw new NotFoundException("복구할 세션이 없습니다");
        }
        int claimed = studySessionRepository.acknowledgeRecovery(userId, submissionStartedAt, clock.instant());
        if (claimed == 0) {
            throw new NotFoundException("복구할 세션이 없습니다"); // 동시 복구 요청이 먼저 확인 처리함
        }
        return aggregate(group);
    }

    /** 제출 그룹은 함께 확정·확인되므로 첫 조각으로 대표 판정한다 — 자동 확정본이면서 아직 미확인이어야 한다. */
    private static boolean isUnacknowledgedAutoFinalized(List<StudySession> group) {
        StudySession representative = group.get(0);
        return representative.isAutoFinalized() && representative.getRecoveryAcknowledgedAt() == null;
    }

    /** 자정 분할 조각들을 한 건으로 집계한다 — 시작=최소, 종료=최대, 시간=합, 날짜=시작 조각의 통계 날짜. */
    private static SessionRecoveryResponse aggregate(List<StudySession> group) {
        StudySession first = group.get(0);
        StudySession last = group.get(group.size() - 1);
        int studySec = group.stream().mapToInt(StudySession::getStudySec).sum();
        int focusSec = group.stream().mapToInt(StudySession::getFocusSec).sum();
        return new SessionRecoveryResponse(
                first.getStatDate(), first.getStartedAt(), last.getEndedAt(), studySec, focusSec);
    }
}
