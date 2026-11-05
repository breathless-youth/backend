package project.study.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserRegisterRequest(
        @Schema(
                description = "앱이 첫 실행 때 생성해 기기 보안 저장소에 보관하는 기기 식별 UUID. 대소문자는 서버가 소문자로 통일한다",
                example = "0f8fad5b-d9cb-469f-a165-70867728950e")
        @NotBlank
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "UUID 형식이어야 합니다")
        String deviceId,

        @Schema(
                description = "휴대폰 언어(BCP 47 태그). 기본 닉네임을 이 언어 목록에서 준다 — 한국어·영어·일본어·중국어 간체·대만 번체·홍콩 번체. "
                        + "목록이 없는 언어(인도 포함)는 영어, 생략하면 한국어",
                example = "ja-JP")
        @Size(max = 64, message = "언어 태그는 64자 이하여야 합니다")
        String language) {

    /** 언어 없이 등록 — 옛 앱과 내부 호출(시더)은 한국어 목록을 받는다. */
    public UserRegisterRequest(String deviceId) {
        this(deviceId, null);
    }
}
