package project.study.user.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.user.dto.LegacyUserRegisterResponse;
import project.study.user.dto.ProfileResponse;
import project.study.user.dto.ProfileUpdateRequest;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.service.UserService;

/**
 * 구 앱(v1.2.x) 전용 유저 API — API-Version 헤더가 없거나 1인 요청만 여기로 온다 (ADR-0020).
 *
 * <p>계약은 v1.2.1 그대로다: 등록은 토큰 없이 {@code {userId, isNew}}, 프로필은 경로의 userId로 식별.
 * 프로필은 토큰 계약 핸들러({@link UserController})에 userId만 바꿔 위임하는 어댑터라 로직이 없다.
 * 강제 업데이트(BY-531) 뒤 contract 시 이 클래스와 {@link LegacyUserRegisterResponse}를 삭제한다.
 */
// Swagger에 싣지 않는다 — springdoc은 같은 경로+메서드를 하나로 합쳐 토큰 계약(v2) 문서를 덮어쓴다.
// 구 앱 계약은 v1.2.1 그대로이며 ADR-0020의 표가 명세다
@Hidden
@RestController
@RequestMapping(value = "/api/users", version = "1")
@RequiredArgsConstructor
public class LegacyUserController {

    private final UserService userService;
    private final UserController userController;

    @PostMapping
    public ResponseEntity<LegacyUserRegisterResponse> register(@Valid @RequestBody UserRegisterRequest request) {
        UserService.RegisterResult result = userService.register(request);
        HttpStatus status = result.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new LegacyUserRegisterResponse(result.userId(), result.isNew()));
    }

    @GetMapping("/{userId}/profile")
    public ProfileResponse getProfile(@PathVariable Long userId) {
        return userController.getProfile(userId);
    }

    @PatchMapping("/{userId}/profile")
    public ProfileResponse updateProfile(@PathVariable Long userId, @Valid @RequestBody ProfileUpdateRequest request) {
        return userController.updateProfile(userId, request);
    }
}
