package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record GroupInviteResponse(
        @Schema(
                description = "초대코드 — 영문 대문자·숫자 6자리(0·O·1·I·L 제외), 대소문자 무시. " + "초대 링크는 서버가 만들지 않고 FE가 이 코드로 만든다",
                example = "K7M2PQ")
        String inviteCode) {}
