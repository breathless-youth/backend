package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import project.study.group.JoinVia;

public record GroupJoinRequest(
        @Schema(
                description = "초대코드 — 영문 대문자·숫자 6자리(0·O·1·I·L 제외), 대소문자 무시. "
                        + "서버는 형식을 따로 검사하지 않는다 — 틀린 형식도 400이 아니라 404 `INVITE_CODE_NOT_FOUND`다. "
                        + "비어 있으면 400 `VALIDATION_FAILED`",
                example = "K7M2PQ")
        @NotBlank
        String inviteCode,

        @Schema(description = "들어온 경로 — `LINK` 초대 링크를 눌러서, `CODE` 코드를 직접 입력해서. 분석에만 쓰고 들어오는 규칙은 같다", example = "LINK")
        @NotNull
        JoinVia via) {}
