package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import project.study.studysession.entity.StudySessionSubjectTime;

/** 저장된 세션의 항목별 시간 1건 — 자정 분할 조각에는 그 조각 몫만 담긴다. */
public record SubjectTimeResponse(
        @Schema(description = "과목 ID", example = "3") Long subjectId,

        @Schema(description = "할 일 ID — 과목만 선택해 잰 시간이면 null", example = "12")
        Long taskId,

        @Schema(description = "이 항목의 총 공부 시간(초)", example = "2400")
        Integer studySec,

        @Schema(description = "이 항목의 순공 시간(초)", example = "2100")
        Integer focusSec) {

    public static SubjectTimeResponse from(StudySessionSubjectTime time) {
        return new SubjectTimeResponse(time.getSubjectId(), time.getTaskId(), time.getStudySec(), time.getFocusSec());
    }
}
