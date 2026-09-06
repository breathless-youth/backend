package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 모든 필드는 null 허용(생략 = 미변경) — Bean Validation 제약은 null을 통과시키므로 부분 수정 의미가 유지된다
public record ProfileUpdateRequest(
        @Schema(description = "변경할 닉네임 — 2~12자, 한글·영문·숫자만. 생략하면 미변경", example = "숨벅찬청년들")
        @Pattern(regexp = "[가-힣a-zA-Z0-9]{2,12}", message = "닉네임은 2~12자의 한글·영문·숫자만 사용할 수 있습니다")
        String nickname,

        @Schema(description = "변경할 한줄 목표 — 공백 포함 20자 이하. 생략하면 미변경", example = "올해 안에 이직 성공")
        @Size(max = 20, message = "목표는 공백 포함 20자 이하여야 합니다")
        String goal,

        @Schema(
                description = "변경할 카테고리. 생략하면 미변경",
                example = "JOB",
                allowableValues = {"PROFESSIONAL", "CSAT", "JOB", "CERTIFICATE", "CIVIL_SERVICE", "LANGUAGE", "ETC"})
        @Pattern(regexp = "PROFESSIONAL|CSAT|JOB|CERTIFICATE|CIVIL_SERVICE|LANGUAGE|ETC", message = "정의되지 않은 카테고리입니다")
        String category) {}
