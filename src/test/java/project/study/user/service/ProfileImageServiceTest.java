package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.user.dto.ProfileImageUploadUrlResponse;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;

@ExtendWith(MockitoExtension.class)
class ProfileImageServiceTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-28T10:05:00Z");

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
}
