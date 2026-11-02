package project.study.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import project.study.room.service.CurrentRoomLeaver;
import project.study.user.dto.AppleClient;
import project.study.user.dto.DeleteAccountRequest;
import project.study.user.entity.Provider;
import project.study.user.image.ProfileImageStorage;
import project.study.user.oauth.AppleTokenRevoker;

/**
 * 계정 삭제(탈퇴). 트랜잭션이 아니다 — 방 나가기와 사진 파일 삭제는 DB 트랜잭션 밖에서 하고, 행 삭제만 {@link AccountEraser}의 한
 * 트랜잭션이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final CurrentRoomLeaver currentRoomLeaver;
    private final AccountEraser accountEraser;
    private final ProfileImageStorage profileImageStorage;
    private final AppleTokenRevoker appleTokenRevoker;

    public void delete(Long userId, DeleteAccountRequest request) {
        currentRoomLeaver.leave(userId);
        accountEraser.erase(userId).ifPresent(erased -> {
            deleteImageQuietly(erased.profileImageKey());
            if (erased.provider() == Provider.APPLE) {
                revokeAppleQuietly(request);
            }
        });
    }

    // 해지는 실패해도 탈퇴를 막지 않는다. 코드는 비밀값이라 로그에 남기지 않는다
    private void revokeAppleQuietly(DeleteAccountRequest request) {
        String code = request == null ? null : request.appleAuthorizationCode();
        if (!StringUtils.hasText(code)) {
            log.warn("애플 계정이 인가 코드 없이 탈퇴해 애플 토큰을 해지하지 못했습니다");
            return;
        }
        AppleClient client = request.appleClient() == null ? AppleClient.IOS : request.appleClient();
        appleTokenRevoker.revoke(code, client);
    }

    // 남은 파일은 어디서도 참조하지 않으므로 지우지 못해도 탈퇴는 성공으로 둔다 (ProfileImageService와 같은 방식)
    private void deleteImageQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            profileImageStorage.delete(key);
        } catch (RuntimeException e) {
            log.warn("탈퇴한 사용자의 프로필 사진 삭제 실패: key={}", key, e);
        }
    }
}
