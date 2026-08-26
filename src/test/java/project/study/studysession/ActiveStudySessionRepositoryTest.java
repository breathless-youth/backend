package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.entity.ActiveStudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;

/** BY-447 draft 테이블 라운드트립 — jsonb 매핑과 파생 쿼리가 실제 PostgreSQL에서 동작하는지 검증한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveStudySessionRepositoryTest {

    @Autowired
    private ActiveStudySessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    private final Instant startedAt = Instant.parse("2026-08-27T01:00:00Z");

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private int upsert(Instant reported, int studySec, int focusSec, String events) {
        return repository.upsertSnapshot(
                userId, startedAt, reported, Instant.parse("2026-08-27T01:10:05Z"), studySec, focusSec, events);
    }

    @Test
    void 없으면_INSERT되고_유저와_시작시각으로_다시_찾는다() {
        String events =
                "[{\"status\":\"PHONE\",\"startedAt\":\"2026-08-27T01:05:00Z\",\"endedAt\":\"2026-08-27T01:07:00Z\"}]";
        int affected = upsert(startedAt.plusSeconds(600), 600, 480, events);

        assertThat(affected).isEqualTo(1);
        ActiveStudySession found =
                repository.findByUserIdAndStartedAt(userId, startedAt).orElseThrow();
        assertThat(found.getReportedAt()).isEqualTo(startedAt.plusSeconds(600));
        assertThat(found.getStudySec()).isEqualTo(600);
        assertThat(found.getFocusSec()).isEqualTo(480);
        assertThat(found.getEvents()).contains("PHONE");
    }

    @Test
    void 있으면_행을_늘리지_않고_통째로_갱신된다() {
        upsert(startedAt.plusSeconds(30), 30, 30, "[]");
        int affected = upsert(startedAt.plusSeconds(60), 60, 55, "[]");

        assertThat(affected).isEqualTo(1);
        ActiveStudySession updated =
                repository.findByUserIdAndStartedAt(userId, startedAt).orElseThrow();
        assertThat(updated.getStudySec()).isEqualTo(60);
        assertThat(repository.findByLastSeenAtBefore(Instant.parse("2026-08-27T02:00:00Z")))
                .hasSize(1);
    }

    @Test
    void 저장된_것보다_과거_reportedAt은_0행_갱신으로_무시된다() {
        upsert(startedAt.plusSeconds(60), 60, 55, "[]");
        int affected = upsert(startedAt.plusSeconds(30), 30, 30, "[]");

        assertThat(affected).isEqualTo(0);
        ActiveStudySession kept =
                repository.findByUserIdAndStartedAt(userId, startedAt).orElseThrow();
        assertThat(kept.getStudySec()).isEqualTo(60);
    }

    @Test
    void lastSeenAt이_기준보다_오래된_draft만_조회된다() {
        upsert(startedAt.plusSeconds(30), 30, 30, "[]");

        assertThat(repository.findByLastSeenAtBefore(Instant.parse("2026-08-27T01:10:06Z")))
                .hasSize(1);
        assertThat(repository.findByLastSeenAtBefore(Instant.parse("2026-08-27T01:10:05Z")))
                .isEmpty();
    }
}
