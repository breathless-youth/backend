package project.study.planner.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.planner.dto.PlannerTask;
import project.study.planner.dto.PlannerTaskRequests.Carry;
import project.study.planner.repository.PlannerStore;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.user.service.UserTimeZones;

@Service
@RequiredArgsConstructor
public class PlannerCarryService {
    private final PlannerCarryCandidates candidates;
    private final PlannerTaskResolver resolver;
    private final PlannerTaskService tasks;
    private final PlannerStore store;
    private final PlannerCapacity capacity;
    private final StudySubjectLockRepository locks;
    private final UserTimeZones timeZones;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<PlannerTask> candidates(Long userId, String timeZone) {
        var zone = UserTimeZones.parse(timeZone).orElseGet(() -> timeZones.zoneOf(userId));
        return candidates.candidates(userId, PlannerCalendar.today(clock, zone));
    }

    @Transactional
    public List<PlannerTask> decide(Long userId, Carry request, String timeZone) {
        validate(request);
        locks.lockUser(userId);
        LocalDate today = PlannerCalendar.today(clock, timeZones.resolve(userId, timeZone));
        Set<String> eligible = candidates.candidates(userId, today).stream()
                .map(PlannerTask::key)
                .collect(Collectors.toSet());
        List<PlannerTask> moved = new ArrayList<>();
        Set<Long> changedSubjects = new HashSet<>();
        for (String key : request.offeredKeys()) {
            PlannerTask task = resolver.get(userId, key);
            boolean selected = request.selectedKeys().contains(key);
            if (task.done())
                throw new ConflictException(ErrorCode.PLANNER_CARRYOVER_CHANGED, "이미 완료한 할 일입니다. 후보를 다시 불러오세요");
            if (selected && task.date().equals(today)) {
                moved.add(task);
                continue;
            }
            if (!selected && task.dismissed()) continue;
            if (!eligible.contains(task.key()))
                throw new ConflictException(ErrorCode.PLANNER_CARRYOVER_CHANGED, "후보가 바뀌었습니다. 다시 불러오세요");
            long id = tasks.materialize(task);
            if (selected) {
                tasks.assertNoDuplicate(userId, task, today);
                store.move(id, today);
                changedSubjects.add(task.subjectId());
                moved.add(resolver.get(userId, task.key()));
            } else store.dismiss(id);
        }
        changedSubjects.forEach(id -> capacity.validate(userId, id));
        return moved;
    }

    private static void validate(Carry request) {
        Set<String> offered = new HashSet<>(request.offeredKeys());
        Set<String> selected = new HashSet<>(request.selectedKeys());
        if (offered.size() != request.offeredKeys().size()
                || selected.size() != request.selectedKeys().size()) throw new BadRequestException("할 일 key에 중복이 있습니다");
        if (!offered.containsAll(selected)) throw new BadRequestException("선택한 항목은 제시한 후보 안에 있어야 합니다");
    }
}
