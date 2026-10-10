package project.study.studysession.service;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.NotFoundException;
import project.study.studysession.buffer.ActiveSnapshotBuffer;
import project.study.studysession.dto.ActiveSessionSnapshotRequest;
import project.study.studysession.dto.ActiveSessionSnapshotResponse;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.ActiveStudySession;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 진행중 세션 스냅샷(draft) 관리 — 하트비트 UPSERT와 무응답 draft 자동 확정 (BY-447). */
@Slf4j
@Service
public class ActiveStudySessionService {

    // V12가 이름 없이 만든 FK의 PostgreSQL 자동 명명 규칙 이름
    private static final String USER_FK_CONSTRAINT = "active_study_session_user_id_fkey";

    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final StudySessionService studySessionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ActiveSnapshotBuffer buffer;

    public ActiveStudySessionService(
            ActiveStudySessionRepository activeStudySessionRepository,
            StudySessionService studySessionService,
            ObjectMapper objectMapper,
            Clock clock,
            ActiveSnapshotBuffer buffer) {
        this.activeStudySessionRepository = activeStudySessionRepository;
        this.studySessionService = studySessionService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.buffer = buffer;
    }

    /**
     * 누적 스냅샷을 draft에 UPSERT한다. 검증은 확정 시 실행될 validateAndBuildSessions를 그대로 호출하고
     * 결과를 버리는 방식으로 재사용한다 — draft가 항상 확정 가능한 상태임을 같은 코드 경로로 보장한다.
     * 동시 INSERT 레이스와 역순 도착은 네이티브 UPSERT가 원자적으로 걸러 조용히 무시된다(0행 갱신).
     */
    public void reportSnapshot(Long userId, ActiveSessionSnapshotRequest request) {
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        // 요청 검증에 활용
        studySessionService.validateAndBuildSessions(
                userId,
                request.startedAt(),
                request.reportedAt(),
                request.studySec(),
                request.focusSec(),
                events,
                request.subjectSegmentsOrEmpty());

        // 코얼레싱 버퍼에 넣고 즉시 반환 — 주기적 벌크 flush로 DB·CPU 부하를 낮춘다 (BY-470).
        // events JSON 직렬화·DB 쓰기는 flush 시점에 세션당 1번만 일어난다.
        // 트레이드오프: 없는 user_id의 404가 flush(비동기)로 이동한다 — 하트비트라 실질 영향 없음.
        buffer.offer(userId, request);
    }

    /**
     * 재접속 복구용 최신 스냅샷 조회 (BY-448) — 로컬 기록을 잃은 클라이언트가 draft를 내려받아 세션을
     * 복원한다. 옛 draft(확정 대기)와 새 draft가 공존할 수 있으므로 마지막 보고(lastSeenAt)가 최신인 것을
     * 준다. 없으면 404 — 이미 자동 확정됐거나 애초에 없던 경우로, 확정본은 통계 조회에 이미 반영돼 있다.
     */
    @Transactional(readOnly = true)
    public ActiveSessionSnapshotResponse findLatestSnapshot(Long userId) {
        ActiveStudySession draft = activeStudySessionRepository
                .findFirstByUserIdOrderByLastSeenAtDesc(userId)
                .orElseThrow(() -> new NotFoundException("진행중인 세션이 없습니다"));
        List<StatusEventRequest> events =
                objectMapper.readValue(draft.getEvents(), new TypeReference<List<StatusEventRequest>>() {});
        return new ActiveSessionSnapshotResponse(
                draft.getStartedAt(),
                draft.getReportedAt(),
                draft.getStudySec(),
                draft.getFocusSec(),
                events,
                // 복구 계약은 시작 오름차순이다 — 앱이 마지막 원소의 과목으로 선택 상태를 복원한다 (보고는 순서가 뒤섞여 올 수 있다)
                parseSubjectSegments(draft).stream()
                        .sorted(Comparator.comparing(SubjectSegmentRequest::startedAt))
                        .toList());
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
                draft.getStartedAt(),
                draft.getReportedAt(),
                draft.getStudySec(),
                draft.getFocusSec(),
                events,
                parseSubjectSegments(draft),
                null); // 자동 확정본에는 완료 할 일이 없다 — 앱이 최종 제출에만 싣는다 (ADR-0022)
        try {
            studySessionService.create(draft.getUserId(), request, true);
        } catch (DuplicateSessionException e) {
            // 별개 제출의 분할 조각과 시각이 충돌 — 기록이 이미 있으니 아래에서 draft만 정리한다
        }
        // create 성공 경로는 이미 트랜잭션 안에서 draft를 지웠으므로 no-op이고,
        // 멱등 반환(클라 제출본 존재)·중복 충돌 경로에서만 실제로 지운다 (deleteById는 없으면 무시)
        activeStudySessionRepository.deleteById(draftId);
    }

    /** V20 이전 draft는 없다(배포 전 교체) — 컬럼 기본값 '[]'라 항상 파싱된다. */
    private List<SubjectSegmentRequest> parseSubjectSegments(ActiveStudySession draft) {
        return objectMapper.readValue(draft.getSubjectSegments(), new TypeReference<List<SubjectSegmentRequest>>() {});
    }

    /**
     * 확정이 불가능한 draft를 폐기한다 — finalizeDraft의 create가 검증 예외로 실패한 뒤 스케줄러가
     * 호출한다. 하트비트 검증이 막았어야 할 데이터라 재시도해도 영원히 실패한다.
     */
    @Transactional
    public void discardDraft(Long draftId) {
        activeStudySessionRepository.deleteById(draftId);
    }

    /** 집중 중 판정 — 마지막 수신이 이 안이어야 한다 (BY-828). 하트비트(30초)를 한 번 놓쳐도 집중 중으로 본다. */
    public static final Duration FOCUSING_WINDOW = Duration.ofSeconds(60);

    /**
     * 진행 중 세션을 랭킹 집계용 조각으로 나눈다 (BY-828, ADR-0028). 확정과 같은 분할(자정·시간대 구간)을 쓰고, 지금 집중 중이면
     * 마지막 수신 뒤 경과 시간(최대 60초)만큼 늘린 가상 스냅샷으로 계산해 asOf 시점 값을 갖게 한다. 하트비트가 끊긴 draft도
     * 자동 확정되면 같은 값이 되므로 포함한다. 읽지 못하는 draft는 건너뛴다 — 폐기는 확정 스케줄러 몫이다.
     */
    @Transactional(readOnly = true)
    public List<LivePiece> livePieces(Instant asOf) {
        List<LivePiece> pieces = new ArrayList<>();
        for (ActiveStudySession draft : activeStudySessionRepository.findAll()) {
            try {
                pieces.addAll(toLivePieces(draft, asOf));
            } catch (RuntimeException e) {
                log.warn("랭킹 집계에서 draft를 건너뜀: draftId={}", draft.getId(), e);
                Sentry.captureException(e);
            }
        }
        return pieces;
    }

    private List<LivePiece> toLivePieces(ActiveStudySession draft, Instant asOf) {
        List<StatusEvent> events =
                objectMapper.readValue(draft.getEvents(), new TypeReference<List<StatusEventRequest>>() {}).stream()
                        .map(StatusEventRequest::toEntity)
                        .sorted(Comparator.comparing(StatusEvent::getStartedAt))
                        .toList();
        boolean focusing = isFocusing(draft, events, asOf);
        int extendSec = focusing
                ? (int) Math.max(
                        0, Duration.between(draft.getLastSeenAt(), asOf).toSeconds())
                : 0;
        List<Instant> cuts = StudySessionSplitter.computeCuts(
                draft.getStartedAt(), draft.getReportedAt().plusSeconds(extendSec));
        List<StudySession> sessions = StudySessionSplitter.buildSessions(
                draft.getUserId(),
                cuts,
                StudySessionSplitter.computeSegmentWeights(cuts, events),
                draft.getStudySec() + extendSec,
                draft.getFocusSec() + extendSec,
                List.of(),
                List.of());
        Instant achievedAt = focusing ? asOf : draft.getLastSeenAt();
        List<LivePiece> pieces = new ArrayList<>(sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            StudySession piece = sessions.get(i);
            pieces.add(new LivePiece(
                    piece.getUserId(),
                    piece.getStatDate(),
                    piece.getFocusSec(),
                    piece.getStudySec(),
                    List.copyOf(piece.getSlots()),
                    i == sessions.size() - 1,
                    focusing,
                    achievedAt));
        }
        return pieces;
    }

    /** 마지막 수신이 60초 안이고 진행 중인 이벤트가 없으면 집중 중이다 — 앱은 진행 중 이벤트를 reportedAt에서 닫아 보낸다. */
    static boolean isFocusing(ActiveStudySession draft, List<StatusEvent> sortedEvents, Instant asOf) {
        if (draft.getLastSeenAt().isBefore(asOf.minus(FOCUSING_WINDOW))) {
            return false;
        }
        return sortedEvents.isEmpty() || !sortedEvents.getLast().getEndedAt().equals(draft.getReportedAt());
    }
}
