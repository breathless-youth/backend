package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 과목 순서 저장 본문 — 드래그가 끝났을 때 살아있는 과목 전체의 순서를 한 번에 보낸다 (ADR-0022). */
public record SubjectOrderRequest(
        @Schema(
                description = "살아있는 내 과목 id를 원하는 순서대로 — 빠진 과목은 기존 순서대로 뒤에 붙고, 남의·지운·없는·중복 id가 섞이면 400",
                example = "[5, 3, 8]")
        @NotNull
        List<@NotNull Long> subjectIds) {}
