package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import project.study.common.category.StudyCategory;
import project.study.common.text.TextLength;
import project.study.group.GroupVisibility;

public record GroupCreateRequest(
        @Schema(
                description = "그룹 이름 — 앞뒤 공백을 뺀 1~20자 (NFC로 맞춘 글자 단위, 이모지 조합은 1자). "
                        + "연락처나 링크가 있으면 400 `CONTACT_NOT_ALLOWED`, 글자 수·누락은 400 `VALIDATION_FAILED`",
                example = "새벽 공시반")
        @NotNull
        @TextLength(min = 1, max = 20)
        String name,

        @Schema(
                description = "공개 여부 — 만든 뒤에는 바꿀 수 없다. `PUBLIC`은 공개 그룹 목록에 나오고 신청을 받아 들어온다. "
                        + "`PRIVATE`은 목록에 나오지 않고 초대코드·링크로만 들어온다",
                example = "PUBLIC")
        @NotNull
        GroupVisibility visibility,

        @Schema(
                description = "시험·목표 9개 중 하나 — `CSAT` 수능, `SCHOOL_EXAM` 내신, `UNIVERSITY_EXAM` 대학 시험, "
                        + "`CIVIL_SERVICE` 공무원, `CERTIFICATE` 자격증, `LANGUAGE` 어학, `JOB` 취업, "
                        + "`PROFESSIONAL` 전문직, `ETC` 기타. 프로필의 category와 같은 값이다",
                example = "CIVIL_SERVICE")
        @NotNull
        StudyCategory category,

        @Schema(
                description = "그룹 소개 — 0~500자 (이름과 같은 글자 수 기준). 생략하거나 비워도 된다. 연락처나 링크가 있으면 400 `CONTACT_NOT_ALLOWED`",
                example = "평일 새벽 5시부터 같이 공부해요")
        @TextLength(max = 500)
        String description) {}
