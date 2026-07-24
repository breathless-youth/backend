package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import project.study.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev") // dev 프로필이라 컨텍스트 기동 시 DevDataSeeder가 자동 실행된다
class DevDataSeederTest {

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long demoUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE provider = 'DEVICE' AND provider_user_id = ?",
                Long.class,
                DevDataSeeder.DEMO_DEVICE_ID);
    }

    private Integer sessionCount(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM study_session WHERE user_id = ?", Integer.class, userId);
    }

    @Test
    void 기동_시_데모_유저와_세션이_시딩된다() {
        Long userId = demoUserId();

        Integer sessions = sessionCount(userId);
        Integer events = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM status_event e JOIN study_session s ON e.session_id = s.id WHERE s.user_id = ?",
                Integer.class,
                userId);

        // 제출 5건 + 자정 분할 1건 = 최소 6행 (오늘 세션이 KST 자정에 걸치는 시각이면 1행 추가될 수 있다)
        assertThat(sessions).isGreaterThanOrEqualTo(6);
        // 이벤트 6개 제출 + 자정에 걸친 PHONE 1건이 2행으로 분할 = 최소 7행
        assertThat(events).isGreaterThanOrEqualTo(7);
    }

    @Test
    void 다시_실행해도_유저와_세션이_중복_생성되지_않는다() {
        Long userId = demoUserId();
        Integer before = sessionCount(userId);

        seeder.run(null);

        Integer demoUsers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE provider = 'DEVICE' AND provider_user_id = ?",
                Integer.class,
                DevDataSeeder.DEMO_DEVICE_ID);
        assertThat(demoUsers).isEqualTo(1);
        assertThat(sessionCount(userId)).isEqualTo(before);
    }
}
