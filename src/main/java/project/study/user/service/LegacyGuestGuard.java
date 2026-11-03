package project.study.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.user.entity.Provider;
import project.study.user.repository.UserRepository;

/**
 * 구 앱(v1.2.x) 무인증 경로의 userId 확인 (ADR-0020, ADR-0041). 이 경로들은 토큰 없이 숫자 userId만으로 사용자를 고른다. 구 앱 사용자는 늘
 * 비회원(DEVICE)이므로, 비회원이 아닌 userId는 없는 사용자와 똑같이 거부해 숫자만 알면 소셜 계정의 기록을 읽고 쓰던 길을 막는다.
 *
 * <p>모든 Legacy* 컨트롤러와 STOMP의 {@code ?userId=} 폴백이 이것만 부른다. 강제 업데이트(BY-531) 뒤 contract 때 함께 지운다.
 */
@Component
@RequiredArgsConstructor
public class LegacyGuestGuard {

    private final UserRepository userRepository;

    /** 실존하는 비회원(DEVICE)인지. */
    @Transactional(readOnly = true)
    public boolean isGuest(long userId) {
        return userRepository
                .findProviderById(userId)
                .filter(provider -> provider == Provider.DEVICE)
                .isPresent();
    }

    /** 실존하는 비회원이 아니면 404 {@code USER_NOT_FOUND} — 없는 사용자와 소셜 계정을 구분해 알려 주지 않는다. */
    public void requireGuest(Long userId) {
        if (userId == null || !isGuest(userId)) {
            throw new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다");
        }
    }
}
