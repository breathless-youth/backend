package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import project.study.common.category.StudyCategory;
import project.study.common.text.TextLength;
import project.study.group.GroupJoinPolicy;

/** 그룹 설정 수정 — 공개 여부는 받지 않는다 (만든 뒤 바꿀 수 없다). */
public record GroupUpdateRequest(
        @Schema(
                description = "그룹 이름 — 앞뒤 공백을 뺀 1~20자 (NFC로 맞춘 글자 단위, 이모지 조합은 1자). "
                        + "연락처나 링크가 있으면 400 `CONTACT_NOT_ALLOWED`, 글자 수·누락은 400 `VALIDATION_FAILED`",
                example = "새벽 공시반")
        @NotNull
        @TextLength(min = 1, max = 20)
        String name,

        @Schema(description = "시험·목표 — 그룹 만들기와 같은 9개 값", example = "CIVIL_SERVICE") @NotNull
        StudyCategory category,

        @Schema(
                description = "그룹 소개 — 0~500자. 이 요청은 전체 교체라 생략하거나 비우면 소개가 지워진다. 연락처나 링크가 있으면 400 `CONTACT_NOT_ALLOWED`",
                example = "평일 새벽 5시부터 같이 공부해요")
        @TextLength(max = 500)
        String description,

        @Schema(description = "입장 방식 — 생략하거나 null이면 유지. 공개 그룹만 OPEN 가능. APPROVAL에서 OPEN으로 바꾸면 모든 PENDING 신청을 알림 없이 닫는다")
        GroupJoinPolicy joinPolicy) {}
