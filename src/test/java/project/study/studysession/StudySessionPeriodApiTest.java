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
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudySessionPeriodApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StudySessionRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    // API 테스트는 @Transactional이 아니라 save()가 커밋된다 — userId가 테스트마다 유니크라 격리는 유지된다
    private void save(LocalDate statDate, String startedAt, int studySec, int focusSec) {
        Instant start = Instant.parse(startedAt);
        repository.save(
                new StudySession(userId, statDate, start, start.plusSeconds(studySec), studySec, focusSec, List.of()));
    }

    @Test
    void 기간_일별집계와_총합_직전기간을_반환한다() throws Exception {
        save(LocalDate.of(2026, 8, 25), "2026-08-25T01:00:00Z", 1800, 1500);
        save(LocalDate.of(2026, 8, 27), "2026-08-27T01:00:00Z", 3600, 3000);
        save(LocalDate.of(2026, 8, 20), "2026-08-20T01:00:00Z", 1200, 1000); // 지난주(compare)

        MvcTestResult result = mvc.get()
                .uri("/api/stats/period")
                .param("userId", String.valueOf(userId))
                .param("from", "2026-08-24")
                .param("to", "2026-08-30")
                .param("compareFrom", "2026-08-17")
                .param("compareTo", "2026-08-23")
                .exchange();

        assertThat(result).hasStatusOk();
        StudyPeriodStatsResponse body =
                objectMapper.readValue(result.getResponse().getContentAsString(), StudyPeriodStatsResponse.class);
        assertThat(body.dailyFocusSec()).hasSize(7); // 월~일 7일 전부
        assertThat(body.totalFocusSec()).isEqualTo(4500L);
        assertThat(body.totalStudySec()).isEqualTo(5400L);
        assertThat(body.previousTotalFocusSec()).isEqualTo(1000L);
    }

    @Test
    void from이_to보다_이후면_400() {
        assertThat(mvc.get()
                        .uri("/api/stats/period")
                        .param("userId", String.valueOf(userId))
                        .param("from", "2026-08-30")
                        .param("to", "2026-08-24")
                        .exchange())
                .hasStatus4xxClientError();
    }

    @Test
    void compare를_한쪽만_지정하면_400() {
        assertThat(mvc.get()
                        .uri("/api/stats/period")
                        .param("userId", String.valueOf(userId))
                        .param("from", "2026-08-24")
                        .param("to", "2026-08-30")
                        .param("compareFrom", "2026-08-17")
                        .exchange())
                .hasStatus4xxClientError();
    }
}
