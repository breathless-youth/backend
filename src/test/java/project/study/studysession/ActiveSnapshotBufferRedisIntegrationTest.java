package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.GenericContainer;
import project.study.TestcontainersConfiguration;
import project.study.studysession.buffer.ActiveSnapshotBuffer;
import project.study.studysession.buffer.RedisSnapshotBufferStore;
import project.study.studysession.buffer.SnapshotBufferStore;

/**
 * BY-492 Redis 저장소 와이어링 검증 — {@code active-session.buffer.store=redis}일 때 API→버퍼(Redis)→flush→DB
 * 전체 흐름이 인메모리와 동일하게 동작하는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {"active-session.buffer.store=redis", "active-session.buffer.flush-interval-ms=3600000"})
class ActiveSnapshotBufferRedisIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    @Autowired
    private SnapshotBufferStore store; // 와이어링 검증 대상

    private Long userId;
    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
        buffer.flush();
    }

    @AfterEach
    void cleanUp() {
        buffer.flush();
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private void report(int studySec, Instant reportedAt) {
        String body = """
                {"userId": %d, "startedAt": "%s", "reportedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(userId, startedAt, reportedAt, studySec, studySec);
        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void store_프로퍼티가_redis면_Redis_저장소가_와이어링된다() {
        assertThat(store).isInstanceOf(RedisSnapshotBufferStore.class);
    }

    @Test
    void redis_버퍼로도_코얼레싱_후_flush가_DB에_최신본을_쓴다() {
        report(60, startedAt.plusSeconds(60));
        report(120, startedAt.plusSeconds(120));

        assertThat(buffer.pendingCount()).isEqualTo(1); // Redis에서 코얼레싱됨
        Integer before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(before).isZero();

        buffer.flush();

        Integer studySec = jdbcTemplate.queryForObject(
                "SELECT study_sec FROM active_study_session WHERE user_id = ?", Integer.class, userId);
        assertThat(studySec).isEqualTo(120);
        assertThat(buffer.pendingCount()).isZero();
    }
}
