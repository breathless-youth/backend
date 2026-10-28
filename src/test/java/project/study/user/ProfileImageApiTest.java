package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
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
import project.study.user.image.ProfileImageStorage;
import project.study.user.image.ProfileImageStorage.PresignedUpload;

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
}
