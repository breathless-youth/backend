package project.study.push.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.push.dto.PushTokenRequest;
import project.study.push.service.PushTokenService;

@Tag(name = "Push", description = """
                서버 발송 푸시용 기기 토큰 (ADR-0033). 앱은 실행·토큰 갱신·사용자 변경 때마다 등록한다.

                구 앱 대응이 없는 새 경로라 `API-Version: 1`(기본버전)이고, 인증은 토큰이 필요하다 (ADR-0015).""")
@RestController
@RequestMapping(value = "/api/push-tokens", version = "1")
@RequiredArgsConstructor
public class PushTokenController {

    private final PushTokenService pushTokenService;

    @Operation(summary = "기기 토큰 등록", description = "같은 토큰이면 알림 권한 상태와 갱신 시각만 바뀐다(멱등). 다른 사용자에게 붙어 있던 토큰이면 지금 사용자로 옮긴다.")
    @ApiResponse(responseCode = "204", description = "등록됨")
    @ApiResponse(responseCode = "400", description = "토큰 누락·4096자 초과, IOS·ANDROID 밖의 플랫폼, 알림 권한 상태 누락")
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@AuthenticationPrincipal Long userId, @Valid @RequestBody PushTokenRequest request) {
        pushTokenService.register(userId, request);
    }
}
