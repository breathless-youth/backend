package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionDetailApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudySessionRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;
    private Long sessionId;

    @BeforeEach
    void seed() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
        // save()가 events를 CascadeType.ALL로 함께 저장한다 — id는 저장 후 엔티티에서 읽는다
        StudySession saved = repository.save(new StudySession(
                userId,
                LocalDate.of(2026, 8, 27),
                Instant.parse("2026-08-27T00:12:00Z"),
                Instant.parse("2026-08-27T01:36:00Z"),
                5040,
                4080,
                List.of(new StatusEvent(
                        EventStatus.PHONE,
                        Instant.parse("2026-08-27T00:34:00Z"),
                        Instant.parse("2026-08-27T00:41:00Z")))));
        sessionId = saved.getId();
    }

    @Test
    void 세션_상세를_이벤트구간까지_반환한다() throws Exception {
        MvcTestResult result = mvc.get()
                .uri("/api/study-sessions/{id}", sessionId)
                .param("userId", String.valueOf(userId))
                .exchange();

        assertThat(result).hasStatusOk();
        StudySessionResponse body =
                objectMapper.readValue(result.getResponse().getContentAsString(), StudySessionResponse.class);
        assertThat(body.id()).isEqualTo(sessionId);
        assertThat(body.focusSec()).isEqualTo(4080);
        assertThat(body.events()).hasSize(1);
    }

    @Test
    void 남의_세션이면_404() {
        Long other = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "other-" + UUID.randomUUID());

        assertThat(mvc.get()
                        .uri("/api/study-sessions/{id}", sessionId)
                        .param("userId", String.valueOf(other))
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void 없는_세션이면_404() {
        assertThat(mvc.get()
                        .uri("/api/study-sessions/{id}", 999999)
                        .param("userId", String.valueOf(userId))
                        .exchange())
                .hasStatus(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
