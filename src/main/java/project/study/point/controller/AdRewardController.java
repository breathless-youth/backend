package project.study.point.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.point.service.AdRewardService;

@Tag(name = "Point earn")
@RestController
@RequestMapping("/api/points/ads")
@RequiredArgsConstructor
public class AdRewardController {

    private final AdRewardService service;

    @Operation(summary = "AdMob 광고 보상 콜백", description = """
                    앱이 부르는 API가 아니다. 사용자가 보상형 광고를 끝까지 보면 구글 서버가 이 주소를 부르고, 서버는 구글 서명을 확인한 뒤 \
                    포인트를 준다. 앱은 광고를 띄울 때 서버 확인 옵션의 `userId`에 사용자 ID(토큰의 sub)를 넣고, 광고가 끝난 뒤 \
                    `GET /api/points/earn`의 `ad.watchedToday`와 잔액을 다시 읽는다(콜백은 몇 초 늦을 수 있다). \
                    하루 한도를 넘은 시청은 지급하지 않는다. 서명이 틀리면 400, 광고 보상이 설정되지 않은 서버면 404.""")
    @SecurityRequirements // 구글이 인증 헤더 없이 보낸다
    @GetMapping(value = "/admob/callback", version = "1")
    public void admobCallback(HttpServletRequest request) {
        // 서명은 쿼리 문자열 원문에 대한 것이라 풀린 파라미터가 아니라 원문을 넘긴다
        service.grant(service.verified(request.getQueryString()));
    }
}
