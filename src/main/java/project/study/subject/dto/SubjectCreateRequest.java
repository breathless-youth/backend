package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubjectCreateRequest(
        @Schema(description = "과목 이름 — 앞뒤 공백은 잘라 저장한다", example = "수학") @NotBlank @Size(max = 50)
        String name) {}
