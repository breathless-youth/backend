package project.study.planner.dto;

import java.time.LocalDate;

public record PlannerDaySummary(
        LocalDate date,
        int taskCount,
        long completedCount,
        long studySec,
        long focusSec,
        boolean studied,
        boolean hasTasks,
        Integer goalSec) {}
