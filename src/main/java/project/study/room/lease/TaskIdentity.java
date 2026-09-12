package project.study.room.lease;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이 JVM의 태스크 ID. Fargate에서는 컨테이너 메타데이터의 TaskARN 마지막 조각(CloudWatch 로그 스트림 이름과
 * 일치)이라 live_task 행에서 로그를 바로 찾아갈 수 있다. 메타데이터가 없으면(로컬·테스트) UUID.
 */
@Component
@Slf4j
public class TaskIdentity {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String id;

    public TaskIdentity(@Value("${ECS_CONTAINER_METADATA_URI_V4:}") String metadataUri) {
        this.id = resolve(metadataUri);
        log.info("태스크 ID: {}", id);
    }

    public String id() {
        return id;
    }

    static String resolve(String metadataUri) {
        if (metadataUri == null || metadataUri.isBlank()) {
            return "local-" + UUID.randomUUID();
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(metadataUri + "/task"))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            return parseTaskId(
                    client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (IOException | RuntimeException e) {
            log.warn("ECS 태스크 메타데이터 조회 실패 — UUID로 대체", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "local-" + UUID.randomUUID();
    }

    static String parseTaskId(String json) {
        JsonNode arn = JSON.readTree(json).path("TaskARN");
        String value = arn.isMissingNode() ? "" : arn.asString();
        if (value.isBlank()) {
            throw new IllegalStateException("TaskARN 없음: " + json);
        }
        return value.substring(value.lastIndexOf('/') + 1);
    }
}
