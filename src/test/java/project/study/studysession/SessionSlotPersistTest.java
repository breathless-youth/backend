package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.service.StudySessionService;

/** 세션을 저장하면 조각마다 시간대 구간별 순공이 study_session_slot에 함께 저장된다 (BY-828). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionSlotPersistTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private StudySessionService studySessionService;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void createUser() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "slot-" + UUID.randomUUID());
    }

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, KST).toInstant();
    }

    private void submit(Instant start, Instant end, int focusSec, boolean autoFinalized) {
        int length = (int) Duration.between(start, end).toSeconds();
        studySessionService.create(
                userId,
                new StudySessionCreateRequest(start, end, length, focusSec, List.of(), null, null),
                autoFinalized);
    }

    private List<String> slotRows() {
        return jdbc.queryForList("""
                SELECT sl.slot || ' ' || sl.slot_date || ' ' || sl.focus_sec
                FROM study_session_slot sl JOIN study_session s ON s.id = sl.session_id
                WHERE s.user_id = ?
                ORDER BY s.started_at, sl.slot_date, sl.slot""", String.class, userId);
    }

    @Test
    void 세션을_저장하면_구간별_순공도_저장된다() {
        submit(at(20, 6, 30), at(20, 7, 30), 3600, false);

        assertThat(slotRows()).containsExactly("DAWN 2026-09-20 1800", "MORNING 2026-09-20 1800");
    }

    @Test
    void 자정을_넘는_세션은_두_조각_모두_시작한_날의_심야다() {
        submit(at(20, 23, 0), at(21, 1, 0), 7200, false);

        assertThat(slotRows()).containsExactly("NIGHT 2026-09-20 3600", "NIGHT 2026-09-20 3600");
    }

    @Test
    void 자동_확정본이_대체되면_구간_행도_새_값으로_바뀐다() {
        submit(at(22, 9, 0), at(22, 9, 30), 1800, true);
        submit(at(22, 9, 0), at(22, 10, 0), 3600, false);

        assertThat(slotRows()).containsExactly("MORNING 2026-09-22 3600");
    }
}
