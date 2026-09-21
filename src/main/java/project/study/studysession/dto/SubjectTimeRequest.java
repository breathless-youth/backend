package project.study.studysession.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import project.study.studysession.entity.StudySessionSubjectTime;

/**
 * 세션 제출·스냅샷·복구에 공통으로 실리는 과목별 시간 1건 — 과목을 선택한 채 잰 총 공부·순공 시간.
 * 앱이 선택 전환 시점의 세션 타이머 값으로 파생한 값을 그대로 보내고, 서버는 범위·소유만 검증한다.
 * 측정 단위는 과목이다 — 할 일은 체크리스트일 뿐 시간이 붙지 않는다.
 */
public record SubjectTimeRequest(
        @Schema(description = "과목 ID — 토큰 유저의 과목이어야 한다. 세션 중 지운 과목도 허용된다(기록은 남긴다)", example = "3") @NotNull
        Long subjectId,

        @Schema(description = "이 항목에서 잰 총 공부 시간(초). 항목들의 합은 세션 studySec 이하여야 하며 벗어나면 400", example = "2400")
        @NotNull
        @PositiveOrZero
        Integer studySec,

        @Schema(description = "이 항목에서 잰 순공 시간(초). 0 이상, 이 항목의 studySec 이하", example = "2100") @NotNull @PositiveOrZero
        Integer focusSec) {

    public StudySessionSubjectTime toEntity() {
        return new StudySessionSubjectTime(subjectId, studySec, focusSec);
    }
}
