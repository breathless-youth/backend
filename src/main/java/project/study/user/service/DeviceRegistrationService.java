package project.study.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.service.AuthService.TokenPair;
import project.study.user.service.UserService.RegisterResult;

/**
 * 토큰 계약(v2)의 기기 등록 — 등록하고 그 비회원에게 토큰 쌍을 준다. 트랜잭션이 아니다: 등록과 발급은 따로 커밋하므로 발급이 실패해도 유저는
 * 남고, 재호출(멱등)이 토큰을 채운다.
 */
@Service
@RequiredArgsConstructor
public class DeviceRegistrationService {

    private final UserService userService;
    private final AuthService authService;

    public record Registration(boolean isNew, TokenPair tokens) {}

    public Registration register(UserRegisterRequest request) {
        RegisterResult result = userService.register(request);
        Optional<TokenPair> tokens = authService.issueDeviceTokens(result.userId(), result.deviceId());
        if (tokens.isEmpty()) {
            // 등록과 발급 사이에 link가 이 비회원을 소셜 계정으로 바꿨다(전환·병합). 그 deviceId는 이제 어느 비회원과도 맞지 않으니
            // 다시 등록하면 새 비회원이 생긴다. 한 번만 더 한다
            result = userService.register(request);
            tokens = authService.issueDeviceTokens(result.userId(), result.deviceId());
        }
        if (tokens.isEmpty()) {
            throw new IllegalStateException("다시 등록한 비회원도 발급 전에 바뀌었습니다: userId=" + result.userId());
        }
        return new Registration(result.isNew(), tokens.get());
    }
}
