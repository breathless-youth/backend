package project.study.acquisition.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.acquisition.dto.AcquisitionRequest;
import project.study.acquisition.dto.AcquisitionResponse;
import project.study.acquisition.service.AcquisitionService;
import project.study.common.exception.ErrorResponse;

@Tag(name = "Acquisition", description = """
                사용자가 앱을 어떻게 알고 설치했는지(첫 유입 경로) 저장 (ADR-0036). 앱은 첫 실행 뒤 등록이 끝나면 한 번 보낸다.

                구 앱 대응이 없는 새 경로라 `API-Version: 1`(기본버전)이고, 인증은 토큰이 필요하다 (ADR-0015).""")
@RestController
@RequestMapping(value = "/api/users/me/acquisition", version = "1")
@RequiredArgsConstructor
public class AcquisitionController {

    private final AcquisitionService acquisitionService;

    @Operation(summary = "첫 유입 경로 저장", description = """
                    Android는 Play 설치 정보 문자열을, iOS는 딥링크로 받은 광고 라벨을 보낸다. 서버가 메타 광고·친구 초대·오가닉 등으로 판별해 저장한다. \
                    처음 저장한 값만 남고 다시 보내도 처음 값을 돌려준다(멱등). 메타 설치 정보 암호문을 풀지 못해도 원문은 저장하고 200으로 답한다.""")
    @ApiResponse(responseCode = "200", description = "저장됐거나, 이미 저장돼 있던 첫 값")
    @ApiResponse(
            responseCode = "400",
            description = "IOS·ANDROID 밖이거나 누락된 플랫폼, 누락된 capturedAt, 4096자를 넘는 installReferrer, 64자를 넘는 deepLinkAd",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "토큰 없음·만료",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public AcquisitionResponse record(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody AcquisitionRequest request) {
        return acquisitionService.record(userId, request);
    }
}
