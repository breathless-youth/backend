package project.study;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * 배포 이미지 빌드는 매번 Gradle 배포판을 새로 받는데 services.gradle.org가 간헐적으로 504를 낸다.
 * `./gradlew wrapper`로 래퍼를 갱신하면 재시도 값이 기본값(0)으로 돌아가므로 여기서 지킨다
 * (docs/runbooks/release.md "배포 중 Gradle 다운로드 실패").
 */
class GradleWrapperRetryTest {
    @Test
    void wrapperRetriesDistributionDownloadWithBackoff() throws IOException {
        Properties wrapper = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("gradle/wrapper/gradle-wrapper.properties"))) {
            wrapper.load(reader);
        }
        assertThat(Integer.parseInt(wrapper.getProperty("retries", "0"))).isGreaterThanOrEqualTo(5);
        assertThat(Long.parseLong(wrapper.getProperty("retryBackOffMs", "0"))).isGreaterThanOrEqualTo(5000);
    }
}
