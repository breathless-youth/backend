package project.study.studysession.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.NotFoundException;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.dto.StudySessionSummaryResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

@Service
@RequiredArgsConstructor
public class StudySessionService {

    private final StudySessionRepository studySessionRepository;
    private final Clock clock;

    @Transactional
    public List<StudySessionResponse> create(StudySessionCreateRequest request) {
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        List<StudySession> sessions =
                StudySession.createAll(request.userId(), request.startedAt(), request.endedAt(), events, clock);
        try {
            List<StudySession> saved = studySessionRepository.saveAll(sessions);
            // FK 위반을 트랜잭션 커밋 전에 감지하기 위해 즉시 flush (한 트랜잭션이라 분할 저장도 원자적)
            studySessionRepository.flush();
            return saved.stream().map(StudySessionResponse::from).toList();
        } catch (DataIntegrityViolationException e) {
            throw new NotFoundException("존재하지 않는 사용자입니다: " + request.userId());
        }
    }

    @Transactional(readOnly = true)
    public StudySessionResponse get(Long id) {
        StudySession session =
                studySessionRepository.findById(id).orElseThrow(() -> new NotFoundException("세션을 찾을 수 없습니다: " + id));
        return StudySessionResponse.from(session);
    }

    @Transactional(readOnly = true)
    public StudySessionListResponse list(Long userId, LocalDate from, LocalDate to) {
        List<StudySession> sessions =
                studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(userId, from, to);
        long totalSessionSec =
                sessions.stream().mapToLong(StudySession::getSessionSec).sum();
        long totalFocusSec =
                sessions.stream().mapToLong(StudySession::getFocusSec).sum();

        // 프론트가 키 존재를 가정할 수 있도록 없는 상태도 0으로 채운다
        Map<EventStatus, Long> eventCounts = new EnumMap<>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) {
            eventCounts.put(status, 0L);
        }
        studySessionRepository
                .countEventsByStatus(userId, from, to)
                .forEach(count -> eventCounts.put(count.status(), count.count()));

        return new StudySessionListResponse(
                sessions.stream().map(StudySessionSummaryResponse::from).toList(),
                totalSessionSec,
                totalFocusSec,
                StudySession.focusRate(totalFocusSec, totalSessionSec),
                eventCounts);
    }
}
