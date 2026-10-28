package project.study.user.service;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.common.exception.BadRequestException;
import project.study.user.dto.ProfileImageUploadUrlRequest;
import project.study.user.dto.ProfileImageUploadUrlResponse;
import project.study.user.dto.ProfileResponse;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;
import project.study.user.image.ProfileImageStorage.StoredObject;

/**
 * 트랜잭션을 걸지 않는다. S3를 부르는 동안 DB 연결을 잡지 않고, 트랜잭션인 {@link UserService} 호출이 돌아온 시점이면 이미 커밋된 뒤라 이전
 * 파일을 그다음에 지울 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageService {

    private static final Pattern KEY =
            Pattern.compile("avatars/(\\d+)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");

    private final UserService userService;
    private final ProfileImageStorage storage;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId, long contentLength) {
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
        PresignedUpload upload = storage.presignPut(key, contentLength);
        return new ProfileImageUploadUrlResponse(upload.url(), key, upload.expiresAt());
    }

    public ProfileResponse updateProfileImage(Long userId, String key) {
        requireOwnKey(userId, key);
        StoredObject stored = storage.head(key).orElseThrow(() -> new BadRequestException("업로드된 사진이 없습니다"));
        if (stored.size() > ProfileImageUploadUrlRequest.MAX_BYTES
                || !ProfileImageStorage.CONTENT_TYPE.equals(stored.contentType())) {
            deleteQuietly(key);
            throw new BadRequestException("사진은 1MB 이하 JPEG여야 합니다");
        }
        return replace(userId, key);
    }

    public ProfileResponse deleteProfileImage(Long userId) {
        return replace(userId, null);
    }

    private ProfileResponse replace(Long userId, String key) {
        UserService.ProfileImageChange change = userService.replaceProfileImage(userId, key);
        String previous = change.previousKey();
        if (previous != null && !previous.equals(key)) {
            deleteQuietly(previous);
        }
        return change.profile();
    }

    private static void requireOwnKey(Long userId, String key) {
        Matcher matcher = key == null ? null : KEY.matcher(key);
        if (matcher == null || !matcher.matches() || !matcher.group(1).equals(String.valueOf(userId))) {
            throw new BadRequestException("사진 키가 올바르지 않습니다");
        }
    }

    // 남은 파일은 어디서도 참조하지 않으므로 지우지 못해도 요청은 성공으로 둔다
    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("프로필 사진 삭제 실패: key={}", key, e);
        }
    }
}
