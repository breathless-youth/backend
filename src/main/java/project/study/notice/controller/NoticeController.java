package project.study.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.notice.dto.NoticeResponse;
import project.study.notice.service.NoticeService;

@Tag(name = "Notice", description = "운영 공지 API")
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "활성 공지 목록 조회", description = """
                    현재 시각이 노출 기간(starts_at 이후, ends_at 이전) 안에 있는 공지를 \
                    최신순(starts_at 내림차순)으로 반환한다. ends_at이 없는 공지는 무기한 노출이다.

                    공지는 로그인 전 사용자에게도 보여야 하는 공개 정보라 인증 없이 호출할 수 있다. \
                    "한 방문에 하나만 표시"와 "다시 보지 않기"는 클라이언트 정책이므로 서버는 \
                    목록을 자르지 않는다.""")
    @ApiResponse(responseCode = "200", description = "활성 공지 목록 — 없으면 빈 배열")
    @GetMapping("/active")
    public List<NoticeResponse> getActiveNotices() {
        return noticeService.getActiveNotices();
    }
}
