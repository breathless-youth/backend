package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;
import project.study.user.image.ProfileImageStorage.StoredObject;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileImageApiTest {

    @TestConfiguration
    static class StorageMockConfig {
        @Bean
        @Primary
        ProfileImageStorage profileImageStorageMock() {
            return mock(ProfileImageStorage.class);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProfileImageStorage storage;

    @BeforeEach
    void resetStorage() {
        reset(storage);
    }

    private long user() {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private static String keyOf(long userId) {
        return "avatars/" + userId + "/" + UUID.randomUUID() + ".jpg";
    }

    private MockMvcTester.MockMvcRequestBuilder uploadUrl(long userId, String body) {
        return mvc.post()
                .uri("/api/users/me/profile-image/upload-url")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void 업로드_URL과_키와_만료_시각을_준다() {
        long userId = user();
        when(storage.presignPut(anyString(), anyLong()))
                .thenReturn(new PresignedUpload("https://s3.example/upload", Instant.parse("2026-10-28T10:05:00Z")));

        assertThat(uploadUrl(userId, "{\"contentLength\": 123456}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.uploadUrl", v -> assertThat(v).isEqualTo("https://s3.example/upload"))
                .hasPathSatisfying(
                        "$.key", v -> assertThat(v).asString().matches("avatars/" + userId + "/[0-9a-f-]{36}\\.jpg"))
                .hasPathSatisfying("$.expiresAt", v -> assertThat(v).isEqualTo("2026-10-28T10:05:00Z"));
        verify(storage).presignPut(startsWith("avatars/" + userId + "/"), eq(123_456L));
    }

    @Test
    void 크기가_1_미만이거나_1MB를_넘거나_없으면_400_VALIDATION_FAILED다() {
        long userId = user();
        for (String body : new String[] {"{\"contentLength\": 0}", "{\"contentLength\": 1048577}", "{}"}) {
            assertThat(uploadUrl(userId, body))
                    .as(body)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("VALIDATION_FAILED"));
        }
    }

    @Test
    void 정확히_1MB는_발급한다() {
        long userId = user();
        when(storage.presignPut(anyString(), anyLong()))
                .thenReturn(new PresignedUpload("https://s3.example/upload", Instant.parse("2026-10-28T10:05:00Z")));

        assertThat(uploadUrl(userId, "{\"contentLength\": 1048576}")).hasStatusOk();
    }

    private void jpegExists() {
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(1000, "image/jpeg")));
    }

    private MockMvcTester.MockMvcRequestBuilder putImage(long userId, String key) {
        return mvc.put()
                .uri("/api/users/me/profile-image")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\": \"" + key + "\"}");
    }

    private String storedKey(long userId) {
        return jdbc.queryForObject("SELECT profile_image FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void 내_키를_등록하면_프로필에_사진_경로가_실린다() {
        long userId = user();
        String key = keyOf(userId);
        jpegExists();

        assertThat(putImage(userId, key))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.profileImageUrl", v -> assertThat(v).isEqualTo("/" + key));
        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.profileImageUrl", v -> assertThat(v).isEqualTo("/" + key));
        verify(storage, never()).delete(any());
    }

    @Test
    void 사진을_바꾸면_새_키를_저장하고_이전_파일을_지운다() {
        long userId = user();
        String first = keyOf(userId);
        String second = keyOf(userId);
        jpegExists();

        assertThat(putImage(userId, first)).hasStatusOk();
        assertThat(putImage(userId, second)).hasStatusOk();

        assertThat(storedKey(userId)).isEqualTo(second);
        verify(storage).delete(first);
    }

    @Test
    void 남의_키는_400_BAD_REQUEST이고_S3를_보지_않는다() {
        long userId = user();

        assertThat(putImage(userId, keyOf(userId + 1)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("BAD_REQUEST"));
        verify(storage, never()).head(any());
    }

    @Test
    void S3에_파일이_없으면_400이다() {
        long userId = user();
        when(storage.head(anyString())).thenReturn(Optional.empty());

        assertThat(putImage(userId, keyOf(userId))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(storedKey(userId)).isNull();
    }

    @Test
    void 크기가_1MB를_넘는_파일은_지우고_400이다() {
        long userId = user();
        String key = keyOf(userId);
        when(storage.head(key)).thenReturn(Optional.of(new StoredObject(1_048_577, "image/jpeg")));

        assertThat(putImage(userId, key)).hasStatus(HttpStatus.BAD_REQUEST);
        verify(storage).delete(key);
        assertThat(storedKey(userId)).isNull();
    }

    @Test
    void 되돌리면_사진_경로가_null이고_이전_파일을_지운다() {
        long userId = user();
        String key = keyOf(userId);
        jpegExists();
        assertThat(putImage(userId, key)).hasStatusOk();

        assertThat(mvc.delete()
                        .uri("/api/users/me/profile-image")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.profileImageUrl", v -> assertThat(v).isNull());
        verify(storage).delete(key);
        assertThat(storedKey(userId)).isNull();
    }

    @Test
    void 사진이_없어도_되돌리기는_200이다() {
        long userId = user();

        assertThat(mvc.delete()
                        .uri("/api/users/me/profile-image")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                        .with(asUser(userId)))
                .hasStatusOk();
        verify(storage, never()).delete(any());
    }
}
