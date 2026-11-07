package project.study.mission.dto;

import java.time.LocalDate;

public record HomeMissionResponse(
        String version,
        LocalDate startsOn,
        int completedSteps,
        Integer currentStep,
        boolean eligible,
        long measuredFocusSec) {}
