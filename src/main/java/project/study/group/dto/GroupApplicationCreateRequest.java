package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import project.study.common.text.TextLength;

public record GroupApplicationCreateRequest(
        @Schema(
                description = "신청 소개 글 — 그룹장이 보고 수락할지 정한다. 앞뒤 공백을 뺀 1~50자 (NFC로 맞춘 글자 단위, 이모지 조합은 1자). "
                        + "연락처나 링크가 있으면 400 `CONTACT_NOT_ALLOWED`, 글자 수·누락은 400 `VALIDATION_FAILED`",
                example = "새벽 5시에 일어나는 공시생이에요")
        @NotNull
        @TextLength(min = 1, max = 50)
        String introduction) {}
