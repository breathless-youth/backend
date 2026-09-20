package project.study.subject.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.dto.SubjectTimeRequest;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.repository.StudySessionSubjectTimeRepository;
import project.study.subject.dto.SubjectResponse;
import project.study.subject.dto.TaskResponse;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.entity.StudySubject;
import project.study.subject.entity.StudyTask;
import project.study.subject.repository.StudySubjectRepository;
import project.study.subject.repository.StudyTaskRepository;

/** 과목 > 할 일 관리와, 세션이 보내는 항목별 시간의 소유 검증 (BY-698, ADR-0021). */
@Service
@RequiredArgsConstructor
public class StudySubjectService {

    /** 시트 스크롤·서버 부담의 안전선 — 실제로 닿을 일은 드물다 (인터뷰 13차 확정). */
    public static final int MAX_SUBJECTS = 20;

    public static final int MAX_TASKS_PER_SUBJECT = 30;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StudySubjectRepository subjectRepository;
    private final StudyTaskRepository taskRepository;
    private final StudySessionSubjectTimeRepository subjectTimeRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SubjectResponse> list(Long userId) {
        return toResponses(subjectRepository.findByUserIdAndDeletedAtIsNullOrderByIdAsc(userId));
    }

    @Transactional
    public SubjectResponse create(Long userId, String name) {
        if (subjectRepository.countByUserIdAndDeletedAtIsNull(userId) >= MAX_SUBJECTS) {
            throw new BadRequestException("과목은 최대 " + MAX_SUBJECTS + "개까지 만들 수 있습니다");
        }
        StudySubject subject = subjectRepository.save(new StudySubject(userId, name.strip()));
        return new SubjectResponse(subject.getId(), subject.getName(), 0, 0, List.of());
    }

    @Transactional
    public SubjectResponse rename(Long userId, Long subjectId, String name) {
        StudySubject subject = ownedSubject(userId, subjectId);
        subject.rename(name.strip());
        return toResponses(List.of(subject)).get(0);
    }

    /** soft delete — 하위 할 일도 함께 숨긴다. 세션에 쌓인 시간 기록은 그대로 남는다. */
    @Transactional
    public void delete(Long userId, Long subjectId) {
        StudySubject subject = ownedSubject(userId, subjectId);
        Instant now = clock.instant();
        subject.delete(now);
        taskRepository.softDeleteBySubjectId(subjectId, now);
    }

    @Transactional
    public TaskResponse addTask(Long userId, Long subjectId, String name) {
        ownedSubject(userId, subjectId);
        if (taskRepository.countBySubjectIdAndDeletedAtIsNull(subjectId) >= MAX_TASKS_PER_SUBJECT) {
            throw new BadRequestException("할 일은 과목당 최대 " + MAX_TASKS_PER_SUBJECT + "개까지 만들 수 있습니다");
        }
        StudyTask task = taskRepository.save(new StudyTask(subjectId, name.strip()));
        return new TaskResponse(task.getId(), task.getName(), task.getDoneAt(), 0, 0);
    }

    @Transactional
    public TaskResponse updateTask(Long userId, Long subjectId, Long taskId, TaskUpdateRequest request) {
        if (request.name() == null && request.done() == null) {
            throw new BadRequestException("바꿀 값이 없습니다 — name 또는 done을 보내야 합니다");
        }
        ownedSubject(userId, subjectId);
        StudyTask task = ownedTask(subjectId, taskId);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new BadRequestException("name은 비어 있을 수 없습니다");
            }
            task.rename(request.name().strip());
        }
        if (Boolean.TRUE.equals(request.done())) {
            task.markDone(clock.instant());
        } else if (Boolean.FALSE.equals(request.done())) {
            task.clearDone();
        }
        return toTaskResponse(task, sumsByTask(List.of(taskId)));
    }

    @Transactional
    public void deleteTask(Long userId, Long subjectId, Long taskId) {
        ownedSubject(userId, subjectId);
        ownedTask(subjectId, taskId).delete(clock.instant());
    }

    /**
     * 세션 제출·스냅샷의 항목이 토큰 유저의 것인지 확인한다 — 위반은 400. 세션 중 지운 과목·할 일은 허용한다:
     * 거절하면 공부 기록 전체가 함께 거절되고, 정책상 삭제해도 시간 기록은 남기기 때문이다.
     */
    @Transactional(readOnly = true)
    public void assertOwned(Long userId, List<SubjectTimeRequest> times) {
        if (times.isEmpty()) {
            return;
        }
        Set<Long> subjectIds = times.stream().map(SubjectTimeRequest::subjectId).collect(toSet());
        Set<Long> owned = subjectRepository.findByIdInAndUserId(subjectIds, userId).stream()
                .map(StudySubject::getId)
                .collect(toSet());
        if (!owned.containsAll(subjectIds)) {
            throw new BadRequestException("사용자의 과목이 아닙니다");
        }
        Set<Long> taskIds = times.stream()
                .map(SubjectTimeRequest::taskId)
                .filter(Objects::nonNull)
                .collect(toSet());
        if (taskIds.isEmpty()) {
            return;
        }
        Map<Long, Long> subjectOfTask =
                taskRepository.findByIdIn(taskIds).stream().collect(toMap(StudyTask::getId, StudyTask::getSubjectId));
        boolean allMatch = times.stream()
                .filter(t -> t.taskId() != null)
                .allMatch(t -> t.subjectId().equals(subjectOfTask.get(t.taskId())));
        if (!allMatch) {
            throw new BadRequestException("할 일이 그 과목의 것이 아닙니다");
        }
    }

    private StudySubject ownedSubject(Long userId, Long subjectId) {
        return subjectRepository
                .findByIdAndUserIdAndDeletedAtIsNull(subjectId, userId)
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다"));
    }

    private StudyTask ownedTask(Long subjectId, Long taskId) {
        return taskRepository
                .findByIdAndSubjectIdAndDeletedAtIsNull(taskId, subjectId)
                .orElseThrow(() -> new NotFoundException("할 일을 찾을 수 없습니다"));
    }

    /** 과목별 응답 조립 — 오늘(KST) 기준으로 보이는 할 일과 누적 시간을 붙인다. */
    private List<SubjectResponse> toResponses(List<StudySubject> subjects) {
        if (subjects.isEmpty()) {
            return List.of();
        }
        List<Long> subjectIds = subjects.stream().map(StudySubject::getId).toList();
        Instant todayStart =
                clock.instant().atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
        Map<Long, List<StudyTask>> tasksBySubject = taskRepository.findVisible(subjectIds, todayStart).stream()
                .collect(groupingBy(StudyTask::getSubjectId));
        Map<Long, SubjectTimeSum> subjectSums = index(subjectTimeRepository.sumBySubjectIds(subjectIds));
        Map<Long, SubjectTimeSum> taskSums = sumsByTask(tasksBySubject.values().stream()
                .flatMap(List::stream)
                .map(StudyTask::getId)
                .toList());

        return subjects.stream()
                .map(subject -> {
                    SubjectTimeSum sum = subjectSums.getOrDefault(subject.getId(), SubjectTimeSum.ZERO);
                    List<TaskResponse> tasks = tasksBySubject.getOrDefault(subject.getId(), List.of()).stream()
                            .map(task -> toTaskResponse(task, taskSums))
                            .toList();
                    return new SubjectResponse(
                            subject.getId(), subject.getName(), sum.studySec(), sum.focusSec(), tasks);
                })
                .toList();
    }

    private Map<Long, SubjectTimeSum> sumsByTask(List<Long> taskIds) {
        return taskIds.isEmpty() ? Map.of() : index(subjectTimeRepository.sumByTaskIds(taskIds));
    }

    private static Map<Long, SubjectTimeSum> index(List<SubjectTimeSum> sums) {
        return sums.stream().collect(toMap(SubjectTimeSum::id, Function.identity()));
    }

    private static TaskResponse toTaskResponse(StudyTask task, Map<Long, SubjectTimeSum> sums) {
        SubjectTimeSum sum = sums.getOrDefault(task.getId(), SubjectTimeSum.ZERO);
        return new TaskResponse(task.getId(), task.getName(), task.getDoneAt(), sum.studySec(), sum.focusSec());
    }
}
