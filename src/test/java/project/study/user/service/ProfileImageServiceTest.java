package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.common.exception.BadRequestException;
import project.study.user.dto.ProfileImageUploadUrlResponse;
import project.study.user.dto.ProfileResponse;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;
import project.study.user.image.ProfileImageStorage.StoredObject;

@ExtendWith(MockitoExtension.class)
class ProfileImageServiceTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-28T10:05:00Z");
    private static final String MINE = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg";
    private static final String OLD = "avatars/12/0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d.jpg";
    private static final ProfileResponse PROFILE = new ProfileResponse("포메", null, null, "포", 3, "/" + MINE);
    private static final StoredObject JPEG = new StoredObject(1_048_576, "image/jpeg");

    @Mock
    private UserService userService;

    @Mock
    private ProfileImageStorage storage;

    @InjectMocks
    private ProfileImageService service;

    @Test
    void 업로드_URL은_본인_폴더의_UUID_jpg_키로_발급한다() {
        when(storage.presignPut(anyString(), eq(123_456L)))
                .thenReturn(new PresignedUpload("https://s3.example/upload", EXPIRES));

        ProfileImageUploadUrlResponse response = service.issueUploadUrl(12L, 123_456);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).presignPut(key.capture(), eq(123_456L));
        assertThat(key.getValue())
                .matches("avatars/12/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");
        assertThat(response)
                .isEqualTo(new ProfileImageUploadUrlResponse("https://s3.example/upload", key.getValue(), EXPIRES));
    }

    @Test
    void 내_키를_등록하면_커밋_뒤에_이전_파일을_지운다() {
        when(storage.head(MINE)).thenReturn(Optional.of(JPEG));
        when(userService.replaceProfileImage(12L, MINE)).thenReturn(new UserService.ProfileImageChange(PROFILE, OLD));

        assertThat(service.updateProfileImage(12L, MINE)).isEqualTo(PROFILE);

        InOrder order = inOrder(userService, storage);
        order.verify(userService).replaceProfileImage(12L, MINE);
        order.verify(storage).delete(OLD);
    }

    @Test
    void 키_형식이_아니거나_남의_키면_S3를_보지_않고_400이다() {
        String[] bad = {
            null,
            "",
            "avatars/13/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
            "avatars/012/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
            "/avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg",
            "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.png",
            "avatars/12/3F2A9C1E-7B4D-4E8A-9C2F-1A2B3C4D5E6F.jpg",
            "avatars/12/x.jpg",
            "avatars/12/../13/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg"
        };
        for (String key : bad) {
            assertThatThrownBy(() -> service.updateProfileImage(12L, key))
                    .as("key=%s", key)
                    .isInstanceOf(BadRequestException.class);
        }
        verifyNoInteractions(storage, userService);
    }

    @Test
    void S3에_파일이_없으면_400이다() {
        when(storage.head(MINE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProfileImage(12L, MINE)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userService);
    }

    @Test
    void 정확히_1MB는_받고_1바이트라도_넘으면_그_파일을_지우고_400이다() {
        when(storage.head(MINE)).thenReturn(Optional.of(new StoredObject(1_048_577, "image/jpeg")));

        assertThatThrownBy(() -> service.updateProfileImage(12L, MINE)).isInstanceOf(BadRequestException.class);
        verify(storage).delete(MINE);
        verifyNoInteractions(userService);
    }

    @Test
    void JPEG가_아니면_그_파일을_지우고_400이다() {
        when(storage.head(MINE)).thenReturn(Optional.of(new StoredObject(1000, "image/png")));

        assertThatThrownBy(() -> service.updateProfileImage(12L, MINE)).isInstanceOf(BadRequestException.class);
        verify(storage).delete(MINE);
        verifyNoInteractions(userService);
    }

    @Test
    void 같은_키를_다시_보내면_아무것도_지우지_않는다() {
        when(storage.head(MINE)).thenReturn(Optional.of(JPEG));
        when(userService.replaceProfileImage(12L, MINE)).thenReturn(new UserService.ProfileImageChange(PROFILE, MINE));

        assertThat(service.updateProfileImage(12L, MINE)).isEqualTo(PROFILE);
        verify(storage, never()).delete(any());
    }

    @Test
    void 이전_파일_삭제가_실패해도_성공한다() {
        when(storage.head(MINE)).thenReturn(Optional.of(JPEG));
        when(userService.replaceProfileImage(12L, MINE)).thenReturn(new UserService.ProfileImageChange(PROFILE, OLD));
        doThrow(new RuntimeException("S3 down")).when(storage).delete(OLD);

        assertThat(service.updateProfileImage(12L, MINE)).isEqualTo(PROFILE);
    }

    @Test
    void 되돌리면_이전_파일을_지우고_사진이_없었으면_아무것도_지우지_않는다() {
        ProfileResponse none = new ProfileResponse("포메", null, null, "포", 3, null);
        when(userService.replaceProfileImage(12L, null))
                .thenReturn(new UserService.ProfileImageChange(none, OLD))
                .thenReturn(new UserService.ProfileImageChange(none, null));

        assertThat(service.deleteProfileImage(12L)).isEqualTo(none);
        verify(storage).delete(OLD);

        service.deleteProfileImage(12L);
        verify(storage).delete(any()); // 두 번째 호출은 지우지 않아 여전히 한 번
    }
}
