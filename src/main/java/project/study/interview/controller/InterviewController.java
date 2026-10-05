package project.study.interview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.interview.dto.InterviewStatusResponse;
import project.study.interview.service.InterviewService;

@Tag(name = "Interview", description = """
                인앱 인터뷰 모집 (BY-821, ADR-0027). 서버는 대상 여부와 on/off·링크만 내려주고, \
                언제·얼마나 자주 띄울지(하루 하나, 카드 X, 신청 후 중단)는 앱이 정한다. \
                1·2번 그룹 모달은 이 API가 아니라 `GET /api/notices/active`의 공지(`audience`)로 내려간다.""")
@RestController
@RequestMapping(value = "/api/interview", version = "1")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    @Operation(summary = "완료 화면 카드·설정 입구 상태", description = """
            완료 화면과 설정 화면에 들어갈 때 부른다. 값은 부를 때마다 새로 판정한다.

            - **카드(`cardEligible`)** = 3번 그룹(최근 168시간 완료 세션 3건 이상) + 인터뷰 전체·카드가 켜져 있음 + 링크 등록됨. \
            완료 세션은 자동 종료가 아니고 순공 10분 이상인 세션이며, 자정에 쪼개진 세션은 1건으로 센다. \
            세션을 제출한 뒤 부르면 방금 끝낸 세션까지 반영된다.
            - **설정 행(`settingsEnabled`)** = 그룹과 무관. 인터뷰 전체·설정 행이 켜져 있고 링크가 등록돼 있으면 true.
            - 꺼진 쪽의 링크는 null이다. 링크의 `NICKNAME` 자리는 앱이 URL 인코딩한 닉네임으로 바꾼다(못 가져오면 빈 값).""")
    @ApiResponse(responseCode = "200", description = "카드·설정 입구의 표시 여부와 구글폼 링크")
    @GetMapping("/status")
    public InterviewStatusResponse status(@AuthenticationPrincipal Long userId) {
        return interviewService.status(userId);
    }
}
