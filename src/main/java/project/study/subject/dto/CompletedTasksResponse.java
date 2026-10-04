package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.studysession.dto.SubjectRef;

public record CompletedTasksResponse(
        @Schema(description = "기간 안에 완료한 할 일 — 완료 시각 오름차순(같으면 id). 없으면 []")
        List<CompletedTaskItem> tasks,

        @Schema(description = "tasks[].subjectId가 가리키는 과목의 이름·색 — id 오름차순, 지운 과목 포함. 없으면 []")
        List<SubjectRef> subjects) {}
