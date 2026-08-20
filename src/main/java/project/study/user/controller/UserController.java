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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.user.dto.ProfileResponse;
import project.study.user.dto.ProfileUpdateRequest;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.service.UserService;

@Tag(name = "User", description = "유저 등록·식별 API 모음 — 로그인 없이 기기 UUID로 사용자를 식별한다 (ADR-0004)")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "익명 기기 유저 등록", description = """
                    로그인이 없는 MVP에서 사용자를 만드는 유일한 방법이다. \
                    앱은 첫 실행 때 **기기 UUID**를 하나 생성해 기기 보안 저장소에 보관하고, \
                    이 API로 보내 우리 서비스의 `userId`를 발급받는다. \
                    이후 모든 API 호출(공부 세션 제출·조회 등)에 이 `userId`를 사용한다.

                    **여러 번 호출해도 안전하다(멱등).** 같은 UUID로 다시 등록하면 새 유저를 만들지 않고 \
                    기존 `userId`를 그대로 돌려준다 — 이때 응답 코드는 `200`, 처음 등록이면 `201`이다. \

                    UUID 대소문자는 서버가 소문자로 통일해 저장하므로, 같은 기기가 표기만 다른 UUID로 \
                    중복 가입되는 일은 없다. 단, 앱 삭제 등으로 기기에 저장된 UUID가 사라지면 기존 \
                    데이터와 다시 연결할 방법이 없다 — 익명 방식의 한계로, 추후 로그인 도입 시 \
                    계정 연동으로 해결할 예정이다.""")
    @ApiResponse(responseCode = "201", description = "신규 등록 — 유저가 새로 생성됐고 `isNew`가 `true`다")
    @ApiResponse(responseCode = "200", description = "재등록(멱등) — 이미 등록된 기기라 기존 `userId`를 반환하고 `isNew`가 `false`다")
    @ApiResponse(
            responseCode = "400",
            description = "deviceId 누락 또는 UUID 형식이 아님",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(name = "형식 오류", value = "{\"message\": \"deviceId: UUID 형식이어야 합니다\"}"),
                                @ExampleObject(name = "값 누락", value = "{\"message\": \"deviceId: 공백일 수 없습니다\"}")
                            }))
    @PostMapping
    public ResponseEntity<UserRegisterResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        UserRegisterResponse response = userService.register(request);
        // 신규 등록은 201, 같은 기기의 재등록(멱등)은 200
        HttpStatus status = response.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @Operation(summary = "프로필 조회", description = """
                    유저의 프로필(닉네임·한줄 목표·카테고리·아바타 정보)을 조회한다. \
                    최초 프로필은 유저 등록(POST /api/users) 시점에 자동 발급된다 — \
                    닉네임 `포메{랜덤4자리}`, goal·category는 null.""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(
            responseCode = "404",
            description = "존재하지 않는 사용자",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{userId}/profile")
    public ProfileResponse getProfile(@PathVariable Long userId) {
        return userService.getProfile(userId);
    }

    @Operation(summary = "프로필 수정", description = """
                    프로필의 일부 필드만 수정한다 — 요청에 담긴 필드만 반영되고 생략한 필드는 유지된다. \
                    닉네임을 바꾸면 아바타 이니셜(`initial`)도 첫 글자로 갱신된다. `colorIndex`는 불변.""")
    @ApiResponse(responseCode = "200", description = "수정 성공 — 수정된 전체 프로필 반환")
    @ApiResponse(
            responseCode = "400",
            description = "검증 실패 — 닉네임 형식(2~12자 한글·영문·숫자), 목표 길이(20자), 카테고리 값",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "닉네임 형식",
                                        value = "{\"message\": \"닉네임은 2~12자의 한글·영문·숫자만 사용할 수 있습니다\"}"),
                                @ExampleObject(name = "목표 길이", value = "{\"message\": \"목표는 공백 포함 20자 이하여야 합니다\"}"),
                                @ExampleObject(name = "카테고리", value = "{\"message\": \"정의되지 않은 카테고리입니다\"}")
                            }))
    @ApiResponse(
            responseCode = "409",
            description = "이미 사용 중인 닉네임",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/{userId}/profile")
    public ProfileResponse updateProfile(@PathVariable Long userId, @RequestBody ProfileUpdateRequest request) {
        return userService.updateProfile(userId, request);
    }
}
