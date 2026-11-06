package project.study.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.user.account.AccountData;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

/**
 * 계정 삭제의 DB 작업 — 한 트랜잭션에서 다른 도메인이 먼저 잡는 행({@link AccountData#lockBeforeUsers})과 사용자 행을 잠그고 모든 사용자
 * 데이터와 users 행을 지운다. refresh 토큰도 처리기가 함께 지워 모든
 * 기기가 로그아웃된다. 방 나가기·사진 파일·애플 해지·카카오 연결 끊기는 트랜잭션 밖이라 {@link AccountDeletionService}가 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccountEraser {

    private final UserRepository userRepository;
    private final AccountData accountData;

    /** 커밋 뒤 정리에 쓸 값. 지운 사용자의 프로바이더·프로바이더 쪽 ID(카카오 연결 끊기용)와 프로필 사진 키(없으면 null). */
    record ErasedAccount(Provider provider, String providerUserId, String profileImageKey) {}

    /** 이미 없는 사용자(두 번째 탈퇴 요청 등)면 빈 값 — 멱등이다. */
    @Transactional
    Optional<ErasedAccount> erase(long userId) {
        // 그룹처럼 users 행보다 먼저 잡는 행이 있는 도메인과 같은 순서로 잡는다 — 거꾸로 잡으면 그룹 참여·신청과 교착한다
        accountData.lockBeforeUsers(userId);
        Optional<User> locked = userRepository.findByIdForUpdate(userId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        User user = locked.get();
        ErasedAccount erased = new ErasedAccount(user.getProvider(), user.getProviderUserId(), user.getProfileImage());
        accountData.erase(userId);
        userRepository.delete(user);
        log.info("계정 삭제: userId={}, provider={}", userId, erased.provider());
        return Optional.of(erased);
    }
}
