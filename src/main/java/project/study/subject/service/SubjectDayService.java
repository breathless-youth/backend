package project.study.subject.service;

import static java.util.stream.Collectors.toMap;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;
import project.study.subject.dto.SubjectDayResponse;
import project.study.subject.entity.CompletionTarget;
import project.study.subject.entity.DailyCompletion;
import project.study.subject.entity.StudySubject;
import project.study.subject.repository.DailyCompletionRepository;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.subject.repository.StudySubjectRepository;

/**
 * 날짜 기준 과목 목록과 날짜별 과목 완료 (BY-1053, ADR-0038). 홈 날짜 줄의 하루는 그 달력 날짜의 05시부터 다음 날 05시(KST)다 —
 * 플래너와 같다(위키 BY-949 §7). 과목 자체의 생성·이름·순서·할 일은 {@link StudySubjectService}다.
 */
@Service
@RequiredArgsConstructor
public class SubjectDayService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 홈 날짜의 하루가 시작하는 시각. */
    static final LocalTime HOME_DAY_START = LocalTime.of(5, 0);

    private final StudySubjectRepository subjectRepository;
    private final StudySubjectLockRepository lockRepository;
    private final StudySessionRepository sessionRepository;
    private final DailyCompletionRepository completionRepository;
    private final Clock clock;

    /**
     * 그날 있던 과목에 그날의 과목별 시간과 완료 여부를 붙인다. 있었는지는 {@link StudySubject#existedDuring} 한 곳이 판단한다.
     * 순서는 살아있는 과목(저장된 순서) → 지운 과목(id). 시간은 그 창에 걸치는 세션(통계 날짜가 그날·다음 날인 것, 길이 무관)의
     * 과목 구간을 창으로 잘라 합산한다 — 자정 분할이라 전날 세션은 창에 닿지 않는다. 할 일은 싣지 않는다(일간 조회의
     * subjects[].tasks가 출처).
     */
    @Transactional(readOnly = true)
    public List<SubjectDayResponse> subjectsOn(Long userId, LocalDate date) {
        Instant dayStart = homeDayStart(date);
        Instant dayEnd = homeDayStart(date.plusDays(1));
        List<StudySubject> live =
                subjectRepository.findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(userId).stream()
                        .filter(subject -> subject.existedDuring(dayStart, dayEnd))
                        .toList();
        List<StudySubject> gone = subjectRepository.findByUserIdAndDeletedAtIsNotNull(userId).stream()
                .filter(subject -> subject.existedDuring(dayStart, dayEnd))
                .sorted(Comparator.comparing(StudySubject::getId))
                .toList();
        List<StudySession> sessions = sessionRepository.findInPeriodWithMinFocusSec(userId, date, date.plusDays(1), 0);
        Map<Long, SubjectTimeSum> times = DaySubjectTimes.sum(sessions, dayStart, dayEnd);
        Map<Long, Instant> completedAt =
                completionRepository
                        .findByUserIdAndCompletedDateAndTargetType(userId, date, CompletionTarget.SUBJECT)
                        .stream()
                        .collect(toMap(DailyCompletion::getTargetId, DailyCompletion::getCompletedAt));
        return Stream.concat(live.stream(), gone.stream())
                .map(subject -> {
                    SubjectTimeSum sum = times.getOrDefault(subject.getId(), SubjectTimeSum.ZERO);
                    Instant at = completedAt.get(subject.getId());
                    return new SubjectDayResponse(
                            subject.getId(),
                            subject.getName(),
                            subject.getColorIndex(),
                            subject.getDeletedAt() != null,
                            sum.studySec(),
                            sum.focusSec(),
                            at != null,
                            at);
                })
                .toList();
    }

    /**
     * 과목을 그 날짜에 완료한 것으로 기록한다 — 이미 기록돼 있으면 그대로 둔다(멱등). 지운 과목도 그날에 있었으면 된다.
     * 미래 날짜(오늘 KST 달력 날짜보다 뒤)와 그날 없던 과목은 400, 내 과목이 아니면 404.
     */
    @Transactional
    public void complete(Long userId, Long subjectId, LocalDate date) {
        assertSubjectOn(userId, subjectId, date);
        lockRepository.lockUser(userId); // 같은 날 같은 과목을 동시에 완료해도 유니크 위반 대신 한 건만 남게
        if (!completionRepository.existsByUserIdAndCompletedDateAndTargetTypeAndTargetId(
                userId, date, CompletionTarget.SUBJECT, subjectId)) {
            completionRepository.save(
                    new DailyCompletion(userId, CompletionTarget.SUBJECT, subjectId, date, clock.instant()));
        }
    }

    /** 완료 취소 — 기록이 없어도 그대로 끝난다(멱등). 내 기록만 지우므로 과목을 따로 확인하지 않는다. */
    @Transactional
    public void uncomplete(Long userId, Long subjectId, LocalDate date) {
        completionRepository.deleteCompletion(userId, date, CompletionTarget.SUBJECT, subjectId);
    }

    private void assertSubjectOn(Long userId, Long subjectId, LocalDate date) {
        if (date.isAfter(clock.instant().atZone(KST).toLocalDate())) {
            throw new BadRequestException("미래 날짜에는 완료를 기록할 수 없습니다");
        }
        StudySubject subject = subjectRepository
                .findByIdAndUserId(subjectId, userId)
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다"));
        if (!subject.existedDuring(homeDayStart(date), homeDayStart(date.plusDays(1)))) {
            throw new BadRequestException("그날에 없던 과목입니다");
        }
    }

    /** 홈 날짜의 하루 시작 — 그 달력 날짜의 05:00 KST. */
    static Instant homeDayStart(LocalDate date) {
        return date.atTime(HOME_DAY_START).atZone(KST).toInstant();
    }
}
