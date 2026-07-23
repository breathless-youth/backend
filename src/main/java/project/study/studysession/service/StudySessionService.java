package project.study.studysession.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.NotFoundException;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.dto.StudySessionSummaryResponse;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

@Service
@RequiredArgsConstructor
public class StudySessionService {

    private final StudySessionRepository studySessionRepository;
    private final Clock clock;

    @Transactional
    public StudySessionResponse create(StudySessionCreateRequest request) {
        List<StatusEvent> events =
                request.events().stream().map(StatusEventRequest::toEntity).toList();
        StudySession session =
                StudySession.create(request.userId(), request.startedAt(), request.endedAt(), events, clock);
        try {
            // FK 위반을 트랜잭션 커밋 전에 감지하기 위해 즉시 flush
            return StudySessionResponse.from(studySessionRepository.saveAndFlush(session));
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
    public List<StudySessionSummaryResponse> list(Long userId, LocalDate from, LocalDate to) {
        return studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(userId, from, to).stream()
                .map(StudySessionSummaryResponse::from)
                .toList();
    }
}
