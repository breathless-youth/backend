package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubjectUpdateRequest(
        @Schema(description = "바꿀 과목 이름", example = "수학 II") @NotBlank @Size(max = 50)
        String name) {}
