package project.study.user.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import project.study.user.image.ProfileImageStorage.PresignedUpload;
import project.study.user.image.ProfileImageStorage.StoredObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ProfileImageStorageTest {

    private static final String KEY = "avatars/12/3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f.jpg";

    // 고정 자격 증명이면 서명은 네트워크 없이 로컬에서 계산된다
    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
            .build();

    private final S3Client s3 = mock(S3Client.class);

    private final ProfileImageStorage storage = new ProfileImageStorage(presigner, s3, "test-avatars");

    @Test
    void 업로드_URL은_크기와_형식을_서명하고_5분_뒤_만료된다() {
        Instant before = Instant.now();

        PresignedUpload upload = storage.presignPut(KEY, 123_456);

        Map<String, String> query = query(upload.url());
        assertThat(upload.url()).startsWith("https://test-avatars.s3.ap-northeast-2.amazonaws.com/" + KEY + "?");
        assertThat(query.get("X-Amz-SignedHeaders").split(";")).contains("content-length", "content-type", "host");
        assertThat(query).containsEntry("X-Amz-Expires", "300");
        // 브라우저 PUT은 체크섬 헤더를 보내지 않는다 — 서명에 체크섬이 끼면 업로드가 거부된다
        assertThat(query.keySet())
                .noneMatch(k -> k.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum")
                        || k.toLowerCase(Locale.ROOT).startsWith("x-amz-sdk-checksum"));
        assertThat(upload.expiresAt())
                .isBetween(before.plusSeconds(299), Instant.now().plusSeconds(301));
    }

    @Test
    void 파일이_있으면_크기와_형식을_준다() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(1000L)
                        .contentType("image/jpeg")
                        .build());

        assertThat(storage.head(KEY)).contains(new StoredObject(1000, "image/jpeg"));
    }

    @Test
    void 파일이_없으면_empty다() {
        doThrow(S3Exception.builder().statusCode(404).build()).when(s3).headObject(any(HeadObjectRequest.class));

        assertThat(storage.head(KEY)).isEmpty();
    }

    @Test
    void 권한_오류_같은_다른_S3_오류는_그대로_던진다() {
        doThrow(S3Exception.builder().statusCode(403).build()).when(s3).headObject(any(HeadObjectRequest.class));

        assertThatThrownBy(() -> storage.head(KEY)).isInstanceOf(S3Exception.class);
    }

    private static Map<String, String> query(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        p -> p[0], p -> URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8)));
    }
}
