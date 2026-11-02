package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import project.study.common.text.TextLength;

/** 신고 사유 — 공부방 멤버 신고와 그룹 신청 신고가 함께 쓴다. */
public record ReportRequest(
        @Schema(
                description = "신고 사유 — 0~100자 (앞뒤 공백을 뺀 NFC 글자 단위). 생략하거나 비워도 된다. 글자 수 위반은 400 `VALIDATION_FAILED`",
                example = "카메라를 켜고 불쾌한 행동을 했어요")
        @TextLength(max = 100)
        String reason) {}
