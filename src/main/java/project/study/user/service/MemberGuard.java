package project.study.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.user.entity.Provider;
import project.study.user.repository.UserRepository;

/** 소셜 로그인한 사용자만 쓰는 기능의 문지기 (BY-1241). 비회원(DEVICE)과 없는 사용자는 회원이 아니다. */
@Component
@RequiredArgsConstructor
public class MemberGuard {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public boolean isMember(long userId) {
        return userRepository
                .findProviderById(userId)
                .filter(provider -> provider != Provider.DEVICE)
                .isPresent();
    }

    /** 회원이 아니면 403 {@code MEMBER_ONLY}. */
    public void requireMember(Long userId) {
        if (userId == null || !isMember(userId)) {
            throw new ForbiddenException(ErrorCode.MEMBER_ONLY, "로그인한 사용자만 쓸 수 있습니다");
        }
    }
}
