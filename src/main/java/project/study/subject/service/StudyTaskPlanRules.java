package project.study.subject.service;

import java.time.LocalDate;
import project.study.common.exception.BadRequestException;
import project.study.planner.service.PlannerCalendar;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.entity.StudyTask;

/** 구 세션 화면의 경로에도 같은 계획 날짜 제약을 적용한다. */
final class StudyTaskPlanRules {
    private StudyTaskPlanRules() {}

    static void change(StudyTask task, TaskUpdateRequest request, LocalDate today) {
        if (task.getPlannedDate() == null) return;
        if (request.name() != null) PlannerCalendar.editable(task.getPlannedDate(), today);
        if (request.done() != null && task.getPlannedDate().isAfter(today))
            throw new BadRequestException("아직 할 일을 완료할 수 없습니다");
    }

    static void delete(StudyTask task, LocalDate today) {
        if (task.getPlannedDate() != null) PlannerCalendar.editable(task.getPlannedDate(), today);
    }
}
