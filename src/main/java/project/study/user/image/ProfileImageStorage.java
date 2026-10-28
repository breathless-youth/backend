package project.study.user.image;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** 프로필 사진 버킷의 S3 호출을 모두 맡는다. */
@Component
public class ProfileImageStorage {

    public static final String CONTENT_TYPE = "image/jpeg";
    static final Duration URL_TTL = Duration.ofMinutes(5);

    private final S3Presigner presigner;
    private final S3Client s3;
    private final String bucket;

    public ProfileImageStorage(
            S3Presigner presigner, S3Client s3, @Value("${app.profile-image.bucket:}") String bucket) {
        this.presigner = presigner;
        this.s3 = s3;
        this.bucket = bucket;
    }

    public record PresignedUpload(String url, Instant expiresAt) {}

    public record StoredObject(long size, String contentType) {}

    // 크기·형식을 서명에 넣어 다른 크기나 형식의 본문은 S3가 거부하게 한다
    public PresignedUpload presignPut(String key, long contentLength) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(CONTENT_TYPE)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(URL_TTL)
                .putObjectRequest(put)
                .build());
        return new PresignedUpload(presigned.url().toString(), presigned.expiration());
    }

    public Optional<StoredObject> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
