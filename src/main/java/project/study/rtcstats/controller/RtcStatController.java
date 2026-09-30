package project.study.rtcstats.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.rtcstats.dto.RtcStatRequest;
import project.study.rtcstats.service.RtcStatService;

@Tag(name = "RTC Stats", description = "WebRTC 연결 통계 수집 — 릴레이 비율·coturn egress 측정용 (BY-490)")
@RestController
@RequestMapping("/api/rtc-stats")
@RequiredArgsConstructor
public class RtcStatController {

    private final RtcStatService rtcStatService;

    @Operation(
            summary = "WebRTC 연결 통계 보고",
            description = "프론트 getStats() 샘플(연결별 candidate 타입·bytes)을 수집한다 — fire-and-forget, 204를 돌려준다.")
    @ApiResponse(responseCode = "204", description = "수집 성공")
    @ApiResponse(responseCode = "400", description = "필수값 누락 또는 candidateType 허용값 위반")
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody RtcStatRequest request) {
        rtcStatService.record(request);
    }
}
