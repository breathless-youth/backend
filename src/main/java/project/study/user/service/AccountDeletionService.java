package project.study.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.room.service.CurrentRoomLeaver;
import project.study.user.dto.DeleteAccountRequest;
import project.study.user.image.ProfileImageStorage;

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

    public void delete(Long userId, DeleteAccountRequest request) {
        currentRoomLeaver.leave(userId);
        accountEraser.erase(userId).ifPresent(erased -> {
            deleteImageQuietly(erased.profileImageKey());
            // 애플 토큰 해지는 여기에 더한다 (BY-1126 Task 8) — erased.provider()와 request를 쓴다
        });
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
