package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;

// 시딩은 프로필이 아니라 스위치가 켠다 (BY-640) — 켜면 컨텍스트 기동 시 DevDataSeeder가 자동 실행된다
@SpringBootTest(properties = "app.seed.enabled=true")
@Import(TestcontainersConfiguration.class)
class DevDataSeederTest {

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private OpenAPI openApi;

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

        // 엣지케이스 5건(+자정 분할 1행) — 랜덤 시딩은 0개인 날도 있어 하한을 보장하지 않는다
        assertThat(sessions).isGreaterThanOrEqualTo(5);
        // 이벤트 7개 제출 + 자정에 걸친 PHONE 1건이 2행으로 분할 = 최소 8행
        assertThat(events).isGreaterThanOrEqualTo(8);
    }

    @Test
    void 랜덤_시딩_구간은_하루_0개_이상_8개_이하_세션이고_세션_없는_날도_있다() {
        Long userId = demoUserId();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        // 5~29일 전은 엣지케이스 세션이 없어 랜덤 시딩만으로 채워지는 구간이다
        List<Integer> dailyCounts = jdbcTemplate.queryForList(
                "SELECT count(*) FROM study_session WHERE user_id = ? AND stat_date BETWEEN ? AND ?"
                        + " GROUP BY stat_date",
                Integer.class,
                userId,
                today.minusDays(29),
                today.minusDays(5));

        // 세션이 있는 날짜만 GROUP BY에 잡히므로, 25일보다 적게 나오면 세션 없는 날이 있다는 뜻이다
        assertThat(dailyCounts).hasSizeLessThan(25);
        assertThat(dailyCounts).allSatisfy(count -> assertThat(count).isBetween(1, 8));
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

    @Test
    void 시딩_스위치가_켜지면_API_문서에_목데이터_안내가_함께_붙는다() {
        assertThat(openApi.getInfo().getDescription()).contains("목데이터 안내").contains(DevDataSeeder.DEMO_DEVICE_ID);
    }
}
