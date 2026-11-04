package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import project.study.user.nickname.ValidNickname;

// 모든 필드는 null 허용(생략 = 미변경) — Bean Validation 제약은 null을 통과시키므로 부분 수정 의미가 유지된다
public record ProfileUpdateRequest(
        @Schema(
                description = "변경할 닉네임 — 한글·영문·숫자·이모지·띄어쓰기만, 앞뒤 공백을 제외하고 2~12자"
                        + "(글자 단위, 이모지 조합은 1자, 중간 띄어쓰기 포함, 이모지 조합을 포함해 최대 100 코드포인트). 생략하면 미변경",
                example = "숨 벅찬 청년들🔥")
        @ValidNickname
        String nickname,

        @Schema(description = "변경할 한줄 목표 — 공백 포함 20자 이하. 생략하면 미변경", example = "올해 안에 이직 성공")
        @Size(max = 20, message = "목표는 공백 포함 20자 이하여야 합니다")
        String goal,

        @Schema(
                description = "변경할 카테고리. 생략하면 미변경",
                example = "JOB",
                allowableValues = {
                    "CSAT",
                    "SCHOOL_EXAM",
                    "UNIVERSITY_EXAM",
                    "CIVIL_SERVICE",
                    "CERTIFICATE",
                    "LANGUAGE",
                    "JOB",
                    "PROFESSIONAL",
                    "ETC"
                })
        // 값은 StudyCategory와 같아야 한다 — 애노테이션 상수라 enum에서 만들 수 없고 StudyCategoryTest가 어긋남을 잡는다
        @Pattern(
                regexp = "CSAT|SCHOOL_EXAM|UNIVERSITY_EXAM|CIVIL_SERVICE|CERTIFICATE|LANGUAGE|JOB|PROFESSIONAL|ETC",
                message = "정의되지 않은 카테고리입니다")
        String category,

        @Schema(
                description = "기기의 IANA 시간대 이름. 스트릭·그날 할 일·05시 하루·D-Day의 오늘을 이 시간대로 정한다. "
                        + "생략하면 미변경이고, 읽을 수 없는 값이면 거절하지 않고 무시한다(저장된 값 유지, 기본 Asia/Seoul). 응답에는 싣지 않는다",
                example = "Asia/Seoul")
        String timeZone) {}
