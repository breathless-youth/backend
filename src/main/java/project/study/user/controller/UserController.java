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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
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
}
