package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskCreateRequest(
        @Schema(description = "할 일 이름 — 앞뒤 공백은 잘라 저장한다", example = "3단원 문제풀기") @NotBlank @Size(max = 100)
        String name) {}
