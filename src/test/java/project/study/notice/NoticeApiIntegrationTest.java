package project.study.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.support.AuthTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// app_config는 컨테이너 DB를 다른 테스트와 공유한다 — 바꾼 값은 테스트 트랜잭션과 함께 롤백한다
@Transactional
class NoticeApiIntegrationTest {

    /** 서비스의 Clock을 이 시각으로 고정해, 경계 조건까지 API 경로에서 결정적으로 검증한다. */
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        // 테스트 클래스들이 컨테이너 DB를 공유하므로 이 API의 관심 테이블만 비운다
        jdbcTemplate.update("DELETE FROM notice");
        setInterviewEnabled(true);
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private void setInterviewEnabled(boolean enabled) {
        jdbcTemplate.update(
                "UPDATE app_config SET config_value = ? WHERE config_key = 'interview.enabled'",
                String.valueOf(enabled));
    }

    private MockMvcTester.MockMvcRequestBuilder get() {
        return mvc.get()
                .uri("/api/notices/active")
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(AuthTestSupport.asUser(userId));
    }

    private void insertTargeted(String title, String audience, boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO notice (title, content, audience, badge_text, button_text, button_url, enabled, starts_at)"
                        + " VALUES (?, '본문', ?, '스타벅스 기프티콘 100% 증정', '인터뷰 신청하기', 'https://forms.example/g', ?, ?)",
                title, audience, enabled, java.sql.Timestamp.from(NOW.minus(1, ChronoUnit.HOURS)));
    }

    /** 마지막 세션이 끝난 지 200시간 — 2번 그룹 */
    private void makeLapsed() {
        Instant ended = NOW.minus(200, ChronoUnit.HOURS);
        jdbcTemplate.update(
                "INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at,"
                        + " study_sec, focus_sec) VALUES (?, DATE '2026-08-10', ?, ?, ?, 1200, 1200)",
                userId,
                java.sql.Timestamp.from(ended.minus(1, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(ended.minus(1, ChronoUnit.HOURS)),
                java.sql.Timestamp.from(ended));
    }

    private void insertNotice(String title, String content, String imageUrl, Instant startsAt, Instant endsAt) {
        jdbcTemplate.update(
                "INSERT INTO notice (title, content, image_url, starts_at, ends_at) VALUES (?, ?, ?, ?, ?)",
                title,
                content,
                imageUrl,
                startsAt == null ? null : java.sql.Timestamp.from(startsAt),
                endsAt == null ? null : java.sql.Timestamp.from(endsAt));
    }

    @Test
    void 활성_공지를_id_title_content_imageUrl_목록으로_반환한다() {
        insertNotice(
                "종일룸이 새로 열렸어요",
                "이제 모든 유저와 함께 공부할 수 있어요.",
                "https://example.com/banner.png",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(1, ChronoUnit.DAYS));

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$[0].id", id -> assertThat(id).isNotNull())
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("종일룸이 새로 열렸어요"))
                .hasPathSatisfying(
                        "$[0].content",
                        content -> assertThat(content).asString().isEqualTo("이제 모든 유저와 함께 공부할 수 있어요."))
                .hasPathSatisfying(
                        "$[0].imageUrl", url -> assertThat(url).asString().isEqualTo("https://example.com/banner.png"));
    }

    @Test
    void 시작_전_공지는_반환하지_않는다() {
        insertNotice("예약 공지", "내일부터 노출", null, NOW.plus(1, ChronoUnit.DAYS), null);

        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("[]");
    }

    @Test
    void 종료_시각이_없는_공지는_무기한_노출된다() {
        insertNotice("무기한 공지", "끝 없이 노출", null, NOW.minus(30, ChronoUnit.DAYS), null);

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("무기한 공지"));
    }

    @Test
    void 활성_공지가_여럿이면_시작_시각_최신순으로_정렬한다() {
        insertNotice("먼저 시작한 공지", "오래된 것", null, NOW.minus(2, ChronoUnit.DAYS), null);
        insertNotice("나중에 시작한 공지", "최신 것", null, NOW.minus(1, ChronoUnit.HOURS), null);

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("나중에 시작한 공지"))
                .hasPathSatisfying(
                        "$[1].title", title -> assertThat(title).asString().isEqualTo("먼저 시작한 공지"));
    }

    @Test
    void 활성_공지가_없으면_빈_배열을_반환한다() {
        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("[]");
    }

    @Test
    void 같은_시작_시각이면_나중에_등록된_공지가_먼저다() {
        // 클라이언트는 목록 첫 항목만 노출하므로 동률 순서가 요청마다 흔들리면 안 된다
        Instant startsAt = NOW.minus(1, ChronoUnit.HOURS);
        insertNotice("먼저 등록", "id가 작다", null, startsAt, null);
        insertNotice("나중 등록", "id가 크다", null, startsAt, null);

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("나중 등록"))
                .hasPathSatisfying(
                        "$[1].title", title -> assertThat(title).asString().isEqualTo("먼저 등록"));
    }

    @Test
    void 시작_시각이_정확히_현재인_공지는_포함된다() {
        // 활성 조건은 starts_at <= now — 시작 경계는 포함이다
        insertNotice("경계 시작 공지", "지금 막 시작", null, NOW, null);

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("경계 시작 공지"));
    }

    @Test
    void 종료_시각이_정확히_현재인_공지는_제외된다() {
        // 활성 조건은 now < ends_at — 종료 경계는 제외라 조기 종료(ends_at = now())가 즉시 반영된다
        insertNotice("경계 종료 공지", "지금 막 종료", null, NOW.minus(1, ChronoUnit.DAYS), NOW);

        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("[]");
    }

    @Test
    void 종료된_공지는_반환하지_않는다() {
        insertNotice("지난 공지", "이미 끝났다", null, NOW.minus(2, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.HOURS));

        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("[]");
    }

    @Test
    void 인증이_없으면_401이다() {
        assertThat(mvc.get()
                        .uri("/api/notices/active")
                        .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 꺼진_공지는_반환하지_않는다() {
        insertTargeted("꺼진 공지", "ALL", false);

        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("[]");
    }

    @Test
    void 배지_버튼_대상을_함께_내려준다() {
        insertTargeted("전원 공지", "ALL", true);

        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        [{"title": "전원 공지", "audience": "ALL", "badgeText": "스타벅스 기프티콘 100% 증정",
                          "buttonText": "인터뷰 신청하기", "buttonUrl": "https://forms.example/g"}]""");
    }

    @Test
    void 세션이_없는_사용자는_1번_공지만_받는다() {
        insertTargeted("1번 공지", "G1_NOT_STARTED", true);
        insertTargeted("2번 공지", "G2_LAPSED", true);

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[{\"title\": \"1번 공지\", \"audience\": \"G1_NOT_STARTED\"}]");
    }

    @Test
    void 오래_쉰_사용자는_2번_공지만_받는다() {
        makeLapsed();
        insertTargeted("1번 공지", "G1_NOT_STARTED", true);
        insertTargeted("2번 공지", "G2_LAPSED", true);

        assertThat(get())
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[{\"title\": \"2번 공지\", \"audience\": \"G2_LAPSED\"}]");
    }

    @Test
    void 인터뷰_전체를_끄면_그룹_공지만_빠지고_전원_공지는_남는다() {
        setInterviewEnabled(false);
        insertTargeted("전원 공지", "ALL", true);
        insertTargeted("1번 공지", "G1_NOT_STARTED", true);

        assertThat(get()).hasStatusOk().bodyJson().isLenientlyEqualTo("[{\"title\": \"전원 공지\"}]");
    }
}
