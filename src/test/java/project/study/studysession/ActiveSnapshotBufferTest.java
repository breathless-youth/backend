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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.studysession.buffer.ActiveSnapshotBuffer;

/**
 * BY-470 체크포인트 코얼레싱 버퍼 — 버퍼링이 켜진(기본) 상태에서 지연 flush·코얼레싱·역순 무시를 검증한다.
 *
 * <p>flush 주기를 아주 크게 잡아 스케줄러 자동 flush를 막고, 테스트가 {@link ActiveSnapshotBuffer#flush()}를
 * 직접 호출해 타이밍을 통제한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "active-session.buffer.flush-interval-ms=3600000")
class ActiveSnapshotBufferTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActiveSnapshotBuffer buffer;

    private Long userId;
    private final Instant startedAt = Instant.now().minusSeconds(7200);

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
        buffer.flush(); // 이전 테스트 잔여 비우기
    }

    @AfterEach
    void cleanUp() {
        buffer.flush();
        jdbcTemplate.update("DELETE FROM active_study_session WHERE user_id = ?", userId);
    }

    private void report(int studySec, int focusSec, Instant reportedAt) {
        String body = """
                {"userId": %d, "startedAt": "%s", "reportedAt": "%s", "studySec": %d, "focusSec": %d, "events": []}""".formatted(userId, startedAt, reportedAt, studySec, focusSec);
        assertThat(mvc.put()
                        .uri("/api/study-sessions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    private Integer draftRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM active_study_session WHERE user_id = ?", Integer.class, userId);
    }

    private Integer draftStudySec() {
        return jdbcTemplate.queryForObject(
                "SELECT study_sec FROM active_study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void flush_전엔_DB에_안쓰고_flush하면_쓴다() {
        report(60, 54, startedAt.plusSeconds(60));

        assertThat(draftRows()).isZero(); // 아직 버퍼에만 있음
        assertThat(buffer.pendingCount()).isEqualTo(1);

        buffer.flush();

        assertThat(draftRows()).isEqualTo(1); // flush 후 DB에 반영
        assertThat(draftStudySec()).isEqualTo(60);
    }

    @Test
    void 같은_세션_여러_스냅샷은_코얼레싱되어_최신본_1행만_쓴다() {
        report(60, 54, startedAt.plusSeconds(60));
        report(120, 108, startedAt.plusSeconds(120));
        report(180, 162, startedAt.plusSeconds(180));

        assertThat(buffer.pendingCount()).isEqualTo(1); // 3번 보내도 1개로 합쳐짐

        buffer.flush();

        assertThat(draftRows()).isEqualTo(1);
        assertThat(draftStudySec()).isEqualTo(180); // 최신본만 저장
    }

    @Test
    void 역순_도착_스냅샷은_무시하고_최신본을_유지한다() {
        report(180, 162, startedAt.plusSeconds(180)); // 최신
        report(60, 54, startedAt.plusSeconds(60)); // 역순 도착(과거 reportedAt)

        buffer.flush();

        assertThat(draftStudySec()).isEqualTo(180); // 최신본 유지
    }
}
