package project.study.planner.dto;

import java.time.LocalDate;
import java.util.List;
import project.study.planner.goal.dto.PlannerGoalResponse;
import project.study.planner.record.PlannerRecordView;

public record PlannerDayResponse(
        LocalDate date,
        String timeZone,
        long studySec,
        long focusSec,
        PlannerGoalResponse goal,
        List<Subject> subjects,
        List<PlannerRecordView> records) {
    public record Subject(
            Long id,
            String name,
            int colorIndex,
            boolean deleted,
            boolean selected,
            long studySec,
            long focusSec,
            List<PlannerTask> tasks) {}
}
