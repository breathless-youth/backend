package project.study.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.exception.ErrorResponse;
import project.study.user.dto.DdayRequest;
import project.study.user.dto.DdayResponse;
import project.study.user.service.UserDdayService;

@Tag(name = "UserDday", description = """
                홈 좌상단 D-Day API. 유저당 1개(제목 + 목표 날짜)라 목록 없이 단일 자원으로 다룬다. \
                남은 일수(D-N)는 서버가 내려주지 않고 클라이언트가 기기 날짜로 계산한다.

                구 앱 대응이 없는 새 경로라 `API-Version: 1`(기본버전)이고, 인증은 그대로 토큰이 필요하다 (ADR-0015).""")
@RestController
@RequestMapping(value = "/api/users/me/dday", version = "1")
@RequiredArgsConstructor
public class UserDdayController {

    private final UserDdayService ddayService;

    @Operation(summary = "내 D-Day 조회", description = "설정한 D-Day가 없으면 본문 없이 204.")
    @ApiResponse(responseCode = "200", description = "제목·목표 날짜")
    @ApiResponse(responseCode = "204", description = "설정한 D-Day 없음")
    @GetMapping
    public ResponseEntity<DdayResponse> get(@AuthenticationPrincipal Long userId) {
        return ddayService
                .find(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "내 D-Day 설정·수정", description = "있으면 덮어쓰고 없으면 만든다(upsert). 제목은 앞뒤 공백을 잘라 저장한다.")
    @ApiResponse(responseCode = "200", description = "저장된 D-Day")
    @ApiResponse(
            responseCode = "400",
            description = "제목 누락·공백·10자 초과, 날짜 누락, 또는 오늘(Asia/Seoul)보다 이른 날짜",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "제목 길이", value = "{\"message\": \"title: 제목은 10자 이하여야 합니다\"}"),
                                @ExampleObject(name = "과거 날짜", value = "{\"message\": \"목표 날짜는 오늘 이후여야 합니다\"}")
                            }))
    @PutMapping
    public DdayResponse put(@AuthenticationPrincipal Long userId, @Valid @RequestBody DdayRequest request) {
        return ddayService.save(userId, request);
    }

    @Operation(summary = "내 D-Day 삭제", description = "없어도 204 — 멱등이다.")
    @ApiResponse(responseCode = "204", description = "삭제됨(또는 원래 없음)")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId) {
        ddayService.delete(userId);
    }
}
