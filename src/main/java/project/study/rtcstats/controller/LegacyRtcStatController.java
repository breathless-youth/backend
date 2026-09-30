package project.study.rtcstats.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.rtcstats.dto.LegacyRtcStatRequest;

/**
 * 구 앱(v1.2.x) 전용 WebRTC 통계 API — API-Version 헤더가 없거나 1인 요청만 여기로 온다 (ADR-0020).
 * 본문 userId로 식별해 {@link RtcStatController}에 위임한다. 강제 업데이트(BY-531) 뒤 contract 시 삭제한다.
 */
// Swagger에 싣지 않는다 — springdoc은 같은 경로+메서드를 하나로 합쳐 토큰 계약(v2) 문서를 덮어쓴다.
// 구 앱 계약은 v1.2.1 그대로이며 ADR-0020의 표가 명세다
@Hidden
@RestController
@RequestMapping(value = "/api/rtc-stats", version = "1")
@RequiredArgsConstructor
public class LegacyRtcStatController {

    private final RtcStatController rtcStatController;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody LegacyRtcStatRequest request) {
        rtcStatController.report(request.userId(), request.toRequest());
    }
}
