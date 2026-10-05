package project.study.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.notice.dto.NoticeResponse;
import project.study.notice.service.NoticeService;

@Tag(name = "Notice", description = "운영 공지 API")
@RestController
@RequestMapping(value = "/api/notices", version = "1")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "활성 공지 목록 조회", description = """
            켜져 있고 노출 기간(starts_at 이후, ends_at 이전) 안인 공지 중 이 사용자가 받을 것만 \
            최신순(starts_at 내림차순, 같으면 나중에 등록한 것 먼저)으로 반환한다. ends_at이 없는 공지는 무기한 노출이다.

            - **대상(`audience`)** — `ALL`은 전원, `G1_NOT_STARTED`·`G2_LAPSED`는 서버가 세션 기록으로 판정한 \
            인터뷰 1·2번 그룹에게만 내려간다(ADR-0027). 인터뷰 모집 전체를 끄면 이 둘은 빠진다.
            - **1번** = 시작한 세션 0건이고 진행 중 세션 없음. **2번** = 마지막 세션이 끝난 지 168시간 이상이고 진행 중 세션 없음. \
            값은 부를 때마다 새로 판정한다.
            - "한 방문에 하나만 표시", "다시 보지 않기", 노출 횟수 제한은 앱 정책이라 서버는 목록을 자르지 않는다.""")
    @ApiResponse(responseCode = "200", description = "활성 공지 목록 — 없으면 빈 배열")
    @GetMapping("/active")
    public List<NoticeResponse> getActiveNotices(@AuthenticationPrincipal Long userId) {
        return noticeService.getActiveNotices(userId);
    }
}
