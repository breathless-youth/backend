package project.study.user.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.user.dto.ProfileImageUploadUrlResponse;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;

@Slf4j
@Service
@RequiredArgsConstructor
// 트랜잭션을 걸지 않는다 — S3 호출 동안 DB 연결을 잡지 않고, UserService 호출이 돌아온 시점을 커밋 뒤로 쓰기 위해서다
public class ProfileImageService {

    private final UserService userService;
    private final ProfileImageStorage storage;

    public ProfileImageUploadUrlResponse issueUploadUrl(Long userId, long contentLength) {
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
        PresignedUpload upload = storage.presignPut(key, contentLength);
        return new ProfileImageUploadUrlResponse(upload.url(), key, upload.expiresAt());
    }
}
