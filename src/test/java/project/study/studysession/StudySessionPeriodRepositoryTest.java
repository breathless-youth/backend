package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.DailyStudyStat;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StudySessionPeriodRepositoryTest {

    @Autowired
    private StudySessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private void save(LocalDate date, Instant startedAt, int studySec, int focusSec) {
        repository.save(new StudySession(
                userId, date, startedAt, startedAt.plusSeconds(studySec), studySec, focusSec, List.of()));
    }

    @Test
    void 일별로_총공부와_순공을_합산하고_짧은세션은_제외한다() {
        LocalDate d1 = LocalDate.of(2026, 8, 24);
        LocalDate d2 = LocalDate.of(2026, 8, 25);
        Instant base = d1.atStartOfDay(ZoneOffset.UTC).toInstant();
        save(d1, base, 3600, 3000);
        save(d1, base.plusSeconds(7200), 1800, 1500); // 같은 날 두 번째
        save(d2, base.plusSeconds(90000), 1200, 1000);
        save(d2, base.plusSeconds(100000), 120, 30); // focusSec 30 < 60 → 제외

        List<DailyStudyStat> stats = repository.findDailyStudyStats(userId, d1, d2, 60);

        assertThat(stats).containsExactly(new DailyStudyStat(d1, 5400L, 4500L), new DailyStudyStat(d2, 1200L, 1000L));
    }

    @Test
    void 기록없는_날은_행이_없다() {
        LocalDate d1 = LocalDate.of(2026, 8, 24);
        save(d1, d1.atStartOfDay(ZoneOffset.UTC).toInstant(), 3600, 3000);

        List<DailyStudyStat> stats = repository.findDailyStudyStats(userId, d1, d1.plusDays(3), 60);

        assertThat(stats).extracting(DailyStudyStat::date).containsExactly(d1);
    }
}
