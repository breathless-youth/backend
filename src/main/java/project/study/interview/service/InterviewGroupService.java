package project.study.interview.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.interview.InterviewGroup;
import project.study.studysession.StudySessionThresholds;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/**
 * 세션 기록으로 인터뷰 대상 그룹을 판정한다 (ADR-0027). 언제·얼마나 자주 띄울지는 웹이 정한다.
 * 기준은 168시간({@link Instant}) 단위라 KST 날짜 변환이 없다.
 */
@Service
@RequiredArgsConstructor
public class InterviewGroupService {

    static final Duration WINDOW = Duration.ofHours(168);
    static final int G3_MIN_COMPLETED = 3;
    // 세션 한 건은 24시간을 넘지 않는다 — 자정 분할 조각을 빠짐없이 읽기 위한 여유
    private static final Duration MAX_SESSION = Duration.ofHours(24);

    private final StudySessionRepository studySessionRepository;
    private final ActiveStudySessionRepository activeStudySessionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public InterviewGroup judge(Long userId) {
        Instant since = clock.instant().minus(WINDOW);
        // 지금 공부 중인 사람에게 '시작 안 한 이유'·'안 돌아온 이유'를 묻지 않는다 — 1·2번 모두에서 뺀다
        boolean inProgress = activeStudySessionRepository.existsByUserId(userId);

        if (!studySessionRepository.existsByUserId(userId)) {
            return inProgress ? InterviewGroup.NONE : InterviewGroup.G1_NOT_STARTED;
        }

        Instant lastEndedAt = studySessionRepository.findLastEndedAt(userId);
        if (lastEndedAt != null && !lastEndedAt.isAfter(since)) {
            return inProgress ? InterviewGroup.NONE : InterviewGroup.G2_LAPSED;
        }

        long completed = studySessionRepository.countCompletedSubmissionsEndedAfter(
                userId, since, since.minus(MAX_SESSION), StudySessionThresholds.MIN_STREAK_FOCUS_SEC);
        return completed >= G3_MIN_COMPLETED ? InterviewGroup.G3_ACTIVE : InterviewGroup.NONE;
    }
}
