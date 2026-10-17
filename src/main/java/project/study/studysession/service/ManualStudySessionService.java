package project.study.studysession.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.dto.ManualStudySessionRequest;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.dto.SubjectRef;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSource;
import project.study.studysession.entity.StudySessionSubjectSegment;
import project.study.studysession.repository.ManualStudySessionLockRepository;
import project.study.studysession.repository.StudySessionRepository;

/**
 * 사용자가 직접 넣는 수동 기록 (ADR-0031).
 * 다른 기록과 겹치는 구간은 빼고 남는 조각만 이벤트 없는 세션(studySec = focusSec = 길이)으로 저장한다.
 * 조각은 겹침으로 잘리고 자정에서 또 나뉘어도 첫 조각의 시작 시각을 submissionStartedAt으로 공유해 한 묶음이 되고,
 * 수정·삭제는 그 묶음 전체에 적용된다.
 * 첫 조각의 시작은 사용자 안에서 유니크라 다른 수동 묶음의 키와도, 측정 제출의 키와도 겹치지 않는다.
 * 요청한 시작 시각을 키로 쓰면 앞이 잘린 두 요청이 같은 키를 가져 한 묶음으로 합쳐진다.
 * 과목 소유 검증은 측정 제출처럼 컨트롤러가 먼저 한다.
 */
@Service
@RequiredArgsConstructor
public class ManualStudySessionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final Duration MIN_PIECE = Duration.ofMinutes(1);

    private final StudySessionRepository studySessionRepository;
    private final ManualStudySessionLockRepository lockRepository;
    private final StudySessionService studySessionService;
    private final SubjectLookupProvider subjectLookupProvider;
    private final Clock clock;

    private record Span(Instant start, Instant end) {}

    @Transactional
    public List<StudySessionResponse> create(Long userId, ManualStudySessionRequest request) {
        // 같은 사용자의 동시 요청 둘이 같은 빈 시간을 읽고 둘 다 저장하지 않게, 겹침 계산 전에 사용자 단위로 줄 세운다
        lockRepository.lockUser(userId);
        Span requested = validatedSpan(request);
        assertSelectable(request.subjectId(), null);
        return save(userId, requested, request.subjectId());
    }

    /** 묶음을 지우고 다시 만든다 — 조각 id가 바뀔 수 있다. 지운 묶음은 겹침 계산에서 자연히 빠진다. */
    @Transactional
    public List<StudySessionResponse> update(Long userId, Long id, ManualStudySessionRequest request) {
        // create와 같은 이유로 묶음을 읽기 전에 잡는다
        lockRepository.lockUser(userId);
        List<StudySession> bundle = bundleOf(userId, id);
        Span requested = validatedSpan(request);
        assertSelectable(request.subjectId(), subjectOf(bundle));
        studySessionRepository.deleteAll(bundle);
        studySessionRepository.flush();
        return save(userId, requested, request.subjectId());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        studySessionRepository.deleteAll(bundleOf(userId, id));
    }

    /** id가 가리키는 조각이 속한 묶음 — 남의 것이거나 없으면 404, 측정 세션이면 409. */
    private List<StudySession> bundleOf(Long userId, Long id) {
        StudySession piece = studySessionRepository
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다"));
        if (!piece.isManual()) {
            throw new ConflictException("측정한 기록은 고치거나 지울 수 없어요");
        }
        return studySessionRepository.findByUserIdAndSubmissionStartedAtAndSourceOrderByStartedAtAsc(
                userId, piece.getSubmissionStartedAt(), StudySessionSource.MANUAL);
    }

    /** 사용자가 넣는 값이라 측정 제출과 달리 시계 오차 여유를 두지 않는다. */
    private Span validatedSpan(ManualStudySessionRequest request) {
        Instant start = request.startedAt().truncatedTo(ChronoUnit.MINUTES);
        Instant end = request.endedAt().truncatedTo(ChronoUnit.MINUTES);
        if (!end.isAfter(start)) {
            throw new InvalidSessionException("1분 이상이어야 해요");
        }
        if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
            throw new InvalidSessionException("24시간을 넘길 수 없어요");
        }
        if (end.isAfter(clock.instant())) {
            throw new InvalidSessionException("지금 이후 시간은 넣을 수 없어요");
        }
        return new Span(start, end);
    }

    /** 지운 과목은 새로 고를 수 없고, 수정에서 묶음의 지금 과목을 그대로 다시 보내는 것만 받는다. */
    private void assertSelectable(Long subjectId, Long currentSubjectId) {
        if (subjectId == null || subjectId.equals(currentSubjectId)) {
            return;
        }
        SubjectRef subject = subjectLookupProvider
                .lookup(List.of(subjectId), List.of())
                .subjects()
                .get(subjectId);
        if (subject != null && subject.deleted()) {
            throw new InvalidSessionException("지운 과목은 고를 수 없어요");
        }
    }

    private static Long subjectOf(List<StudySession> bundle) {
        return bundle.stream()
                .flatMap(session -> session.getSubjectSegments().stream())
                .map(StudySessionSubjectSegment::getSubjectId)
                .findFirst()
                .orElse(null);
    }

    private List<StudySessionResponse> save(Long userId, Span requested, Long subjectId) {
        List<Span> free = freeSpans(userId, requested);
        if (free.isEmpty()) {
            throw new DuplicateSessionException("이미 기록이 있는 시간이에요");
        }
        Instant bundleKey = free.getFirst().start();
        List<StudySession> sessions = new ArrayList<>();
        for (Span span : free) {
            List<Instant> cuts = StudySessionSplitter.computeCuts(span.start(), span.end());
            for (int i = 0; i < cuts.size() - 1; i++) {
                sessions.add(manualPiece(userId, cuts.get(i), cuts.get(i + 1), subjectId, bundleKey));
            }
        }
        try {
            List<StudySession> saved = studySessionRepository.saveAll(sessions);
            studySessionRepository.flush();
            return studySessionService.toResponses(saved);
        } catch (DataIntegrityViolationException e) {
            // 같은 값을 동시에 두 번 보내면 둘 다 빈 시간으로 계산하고, 늦은 쪽이 시작 시각 유니크 제약에 걸린다
            if (StudySessionService.STARTED_AT_UNIQUE_CONSTRAINT.equalsIgnoreCase(
                    StudySessionService.violatedConstraint(e))) {
                throw new DuplicateSessionException("이미 기록이 있는 시간이에요");
            }
            throw e;
        }
    }

    /**
     * 요청 구간에서 다른 기록(출처 무관)이 차지한 구간을 빼고 남는 조각.
     * 측정 세션의 경계는 초 이하까지 있으므로 남는 조각을 안쪽으로 분 단위에 맞추고, 1분 미만은 버린다.
     */
    private List<Span> freeSpans(Long userId, Span requested) {
        List<Span> free = new ArrayList<>();
        Instant cursor = requested.start();
        for (StudySession taken : studySessionRepository.findOverlapping(userId, requested.start(), requested.end())) {
            addIfLongEnough(free, cursor, taken.getStartedAt());
            if (taken.getEndedAt().isAfter(cursor)) {
                cursor = taken.getEndedAt();
            }
        }
        addIfLongEnough(free, cursor, requested.end());
        return free;
    }

    private static void addIfLongEnough(List<Span> free, Instant start, Instant end) {
        Instant alignedStart = ceilToMinute(start);
        Instant alignedEnd = end.truncatedTo(ChronoUnit.MINUTES);
        if (Duration.between(alignedStart, alignedEnd).compareTo(MIN_PIECE) >= 0) {
            free.add(new Span(alignedStart, alignedEnd));
        }
    }

    private static Instant ceilToMinute(Instant instant) {
        Instant floor = instant.truncatedTo(ChronoUnit.MINUTES);
        return floor.equals(instant) ? floor : floor.plus(MIN_PIECE);
    }

    private static StudySession manualPiece(
            Long userId, Instant start, Instant end, Long subjectId, Instant submissionStartedAt) {
        int sec = (int) Duration.between(start, end).toSeconds();
        StudySession session =
                new StudySession(userId, start.atZone(KST).toLocalDate(), start, end, sec, sec, List.of());
        if (subjectId != null) {
            session.attachSubjectSegments(List.of(new StudySessionSubjectSegment(subjectId, start, end, sec, sec)));
        }
        session.attachToSubmission(submissionStartedAt);
        session.markManual();
        return session;
    }
}
