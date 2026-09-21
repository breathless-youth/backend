package project.study.subject.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.dto.SubjectTimeRequest;
import project.study.studysession.dto.SubjectTimeSum;
import project.study.studysession.repository.StudySessionSubjectTimeRepository;
import project.study.subject.dto.SubjectResponse;
import project.study.subject.dto.TaskResponse;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.entity.StudySubject;
import project.study.subject.entity.StudyTask;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.subject.repository.StudySubjectRepository;
import project.study.subject.repository.StudyTaskRepository;

/** 과목 > 할 일 관리와, 세션이 보내는 항목별 시간의 소유 검증 (BY-698, ADR-0021). 순서·색은 ADR-0022. */
@Service
@RequiredArgsConstructor
public class StudySubjectService {

    /** 시트 스크롤·서버 부담의 안전선 — 실제로 닿을 일은 드물다 (인터뷰 13차 확정). */
    public static final int MAX_SUBJECTS = 20;

    public static final int MAX_TASKS_PER_SUBJECT = 30;

    /** 색 팔레트 크기 — 앱이 colorIndex를 팔레트에 매핑한다. 과목 상한과 같아 덜 쓴 색 배정이면 20개가 전부 다른 색이다. */
    public static final int SUBJECT_COLOR_COUNT = 20;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StudySubjectRepository subjectRepository;
    private final StudySubjectLockRepository lockRepository;
    private final StudyTaskRepository taskRepository;
    private final StudySessionSubjectTimeRepository subjectTimeRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SubjectResponse> list(Long userId) {
        return toResponses(liveSubjects(userId));
    }

    /** 상한·다음 순서·색은 전부 살아있는 과목 목록(≤ 20건) 한 번으로 계산한다 — 별도 집계 쿼리를 두지 않는다. */
    @Transactional
    public SubjectResponse create(Long userId, String name) {
        lockRepository.lockUser(userId); // 동시 생성이 같은 색·순서를 받지 않게 사용자 단위로 직렬화
        List<StudySubject> live = liveSubjects(userId);
        if (live.size() >= MAX_SUBJECTS) {
            throw new BadRequestException("과목은 최대 " + MAX_SUBJECTS + "개까지 만들 수 있습니다");
        }
        StudySubject subject = subjectRepository.save(
                new StudySubject(userId, name.strip(), nextSortOrder(live), leastUsedColor(live)));
        return new SubjectResponse(subject.getId(), subject.getName(), subject.getColorIndex(), 0, 0, List.of());
    }

    @Transactional
    public SubjectResponse rename(Long userId, Long subjectId, String name) {
        StudySubject subject = ownedSubject(userId, subjectId);
        subject.rename(name.strip());
        return toResponses(List.of(subject)).get(0);
    }

    /**
     * 순서를 통째로 저장한다 — 보낸 순서대로 0부터, 빠진 살아있는 과목은 기존 순서 그대로 뒤에 (ADR-0022).
     * 남의·지운·없는·중복 id가 섞이면 400이고 아무것도 바뀌지 않는다. 동시 저장은 행마다 나중 쓰기가 이기지만
     * 목록이 (sort_order, id)로 정렬되므로 어떤 조합이든 전순서다.
     */
    @Transactional
    public List<SubjectResponse> reorder(Long userId, List<Long> subjectIds) {
        Set<Long> listed = new HashSet<>(subjectIds);
        if (listed.size() != subjectIds.size()) {
            throw new BadRequestException("subjectIds에 중복이 있습니다");
        }
        lockRepository.lockUser(userId);
        List<StudySubject> live = liveSubjects(userId);
        Map<Long, StudySubject> byId = live.stream().collect(toMap(StudySubject::getId, Function.identity()));
        if (!byId.keySet().containsAll(listed)) {
            throw new BadRequestException("사용자의 과목이 아닙니다");
        }
        List<StudySubject> ordered =
                new ArrayList<>(subjectIds.stream().map(byId::get).toList());
        live.stream().filter(subject -> !listed.contains(subject.getId())).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).changeSortOrder(i);
        }
        return toResponses(ordered);
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
        return new TaskResponse(task.getId(), task.getName(), task.getDoneAt());
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
        return toTaskResponse(task);
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
    }

    /**
     * 세션 제출의 완료 할 일이 토큰 유저의 것인지 확인하고, 자정 분할 귀속에 쓸 완료 시각을 붙여 돌려준다 (ADR-0022).
     * 과목(assertOwned)과 같은 이유로 삭제 여부는 보지 않는다. 위반은 400.
     */
    @Transactional(readOnly = true)
    public List<CompletedTask> assertTasksOwned(Long userId, List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = Set.copyOf(taskIds);
        List<StudyTask> owned = taskRepository.findByIdInAndOwner(ids, userId);
        if (owned.size() != ids.size()) {
            throw new BadRequestException("사용자의 할 일이 아닙니다");
        }
        return owned.stream()
                .map(task -> new CompletedTask(task.getId(), task.getDoneAt()))
                .toList();
    }

    private List<StudySubject> liveSubjects(Long userId) {
        return subjectRepository.findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(userId);
    }

    /** 새 과목은 맨 뒤 — 살아있는 과목의 최대 순서 + 1. 개수를 쓰면 중간 삭제로 생긴 빈자리 뒤의 값과 겹친다. */
    private static int nextSortOrder(List<StudySubject> live) {
        return live.stream().mapToInt(StudySubject::getSortOrder).max().orElse(-1) + 1;
    }

    /**
     * 살아있는 과목이 가장 적게 쓴 색, 동률이면 작은 번호 — 팔레트 크기까지는 절대 겹치지 않고 지운 과목의 색은 풀린다.
     * 랜덤이면 과목 5개만 돼도 겹칠 확률이 40%를 넘는다.
     */
    static int leastUsedColor(List<StudySubject> live) {
        int[] used = new int[SUBJECT_COLOR_COUNT];
        for (StudySubject subject : live) {
            used[Math.floorMod(subject.getColorIndex(), SUBJECT_COLOR_COUNT)]++;
        }
        int best = 0;
        for (int color = 1; color < SUBJECT_COLOR_COUNT; color++) {
            if (used[color] < used[best]) {
                best = color;
            }
        }
        return best;
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

    /** 과목별 응답 조립 — 오늘(KST) 기준으로 보이는 할 일과 과목 누적 시간을 붙인다. 입력 순서를 그대로 지킨다. */
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

        return subjects.stream()
                .map(subject -> {
                    SubjectTimeSum sum = subjectSums.getOrDefault(subject.getId(), SubjectTimeSum.ZERO);
                    List<TaskResponse> tasks = tasksBySubject.getOrDefault(subject.getId(), List.of()).stream()
                            .map(StudySubjectService::toTaskResponse)
                            .toList();
                    return new SubjectResponse(
                            subject.getId(),
                            subject.getName(),
                            subject.getColorIndex(),
                            sum.studySec(),
                            sum.focusSec(),
                            tasks);
                })
                .toList();
    }

    private static Map<Long, SubjectTimeSum> index(List<SubjectTimeSum> sums) {
        return sums.stream().collect(toMap(SubjectTimeSum::id, Function.identity()));
    }

    private static TaskResponse toTaskResponse(StudyTask task) {
        return new TaskResponse(task.getId(), task.getName(), task.getDoneAt());
    }
}
