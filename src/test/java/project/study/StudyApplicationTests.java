package project.study;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StudyApplicationTests {

    @Test
    void contextLoads() {
        // Testcontainers PostgreSQL + Flyway 포함 컨텍스트 기동 검증
    }
}
