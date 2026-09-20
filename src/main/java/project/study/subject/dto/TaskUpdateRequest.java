package project.study.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 이름 변경과 완료 토글을 한 엔드포인트로 받는다 — 둘 중 보낸 것만 바뀌고, 둘 다 없으면 400. */
public record TaskUpdateRequest(
        @Schema(description = "바꿀 할 일 이름 — 생략하면 그대로", example = "3단원 문제풀기") @Size(max = 100)
        String name,

        @Schema(
                description = "true면 지금 완료 처리(doneAt = 서버 현재 시각), false면 완료 해제(doneAt = null). 생략하면 그대로",
                example = "true")
        Boolean done) {}
