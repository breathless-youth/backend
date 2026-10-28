package project.study.push.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.push.dto.PushResult;
import project.study.push.dto.PushTestRequest;
import project.study.push.service.PushSender;

/**
 * 시나리오가 없는 동안 발송 기반을 실기기로 확인하는 경로. 요청한 본인의 기기로만 보낸다.
 *
 * <p>값이 없으면 꺼지는 게 기본이라 운영에는 경로 자체가 없고, application-dev.yaml에서만 켠다 (BY-640의 시딩·API 문서 토글과 같은 방식).
 */
@Tag(name = "Push")
@RestController
@RequestMapping(value = "/api/push-tokens/test", version = "1")
@ConditionalOnProperty(name = "app.push.test-send.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PushTestController {

    private final PushSender pushSender;

    @Operation(
            summary = "내 기기로 테스트 알림 보내기 (dev 전용)",
            description = "type은 항상 test다. 빠진 값은 기본 문구로 채운다. sent는 FCM 접수 수이지 도착 수가 아니다.")
    @ApiResponse(responseCode = "200", description = "발송 결과 개수")
    @PostMapping
    public PushResult send(
            @AuthenticationPrincipal Long userId, @RequestBody(required = false) PushTestRequest request) {
        PushTestRequest body = request == null ? new PushTestRequest(null, null, null) : request;
        return pushSender.send(List.of(userId), body.toMessage());
    }
}
