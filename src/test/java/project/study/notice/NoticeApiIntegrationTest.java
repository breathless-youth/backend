package project.study.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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

    @BeforeEach
    void clearNotices() {
        // 테스트 클래스들이 컨테이너 DB를 공유하므로 이 API의 관심 테이블만 비운다
        jdbcTemplate.update("DELETE FROM notice");
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

        assertThat(mvc.get().uri("/api/notices/active"))
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

        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[]");
    }

    @Test
    void 종료_시각이_없는_공지는_무기한_노출된다() {
        insertNotice("무기한 공지", "끝 없이 노출", null, NOW.minus(30, ChronoUnit.DAYS), null);

        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("무기한 공지"));
    }

    @Test
    void 활성_공지가_여럿이면_시작_시각_최신순으로_정렬한다() {
        insertNotice("먼저 시작한 공지", "오래된 것", null, NOW.minus(2, ChronoUnit.DAYS), null);
        insertNotice("나중에 시작한 공지", "최신 것", null, NOW.minus(1, ChronoUnit.HOURS), null);

        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("나중에 시작한 공지"))
                .hasPathSatisfying(
                        "$[1].title", title -> assertThat(title).asString().isEqualTo("먼저 시작한 공지"));
    }

    @Test
    void 활성_공지가_없으면_빈_배열을_반환한다() {
        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[]");
    }

    @Test
    void 인증_없이_호출할_수_있다() {
        // 모든 요청이 인증 필수인 SecurityConfig에서 이 GET만 permitAll이다(BY-376).
        // 위 테스트들도 인증 헤더가 없지만, permitAll 제거 시 실패 이유가 이 테스트 이름으로 드러난다.
        insertNotice("공개 공지", "로그인 전에도 보인다", null, NOW.minus(1, ChronoUnit.HOURS), null);

        assertThat(mvc.get().uri("/api/notices/active")).hasStatusOk();
    }

    @Test
    void 같은_시작_시각이면_나중에_등록된_공지가_먼저다() {
        // 클라이언트는 목록 첫 항목만 노출하므로 동률 순서가 요청마다 흔들리면 안 된다
        Instant startsAt = NOW.minus(1, ChronoUnit.HOURS);
        insertNotice("먼저 등록", "id가 작다", null, startsAt, null);
        insertNotice("나중 등록", "id가 크다", null, startsAt, null);

        assertThat(mvc.get().uri("/api/notices/active"))
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

        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$[0].title", title -> assertThat(title).asString().isEqualTo("경계 시작 공지"));
    }

    @Test
    void 종료_시각이_정확히_현재인_공지는_제외된다() {
        // 활성 조건은 now < ends_at — 종료 경계는 제외라 조기 종료(ends_at = now())가 즉시 반영된다
        insertNotice("경계 종료 공지", "지금 막 종료", null, NOW.minus(1, ChronoUnit.DAYS), NOW);

        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[]");
    }

    @Test
    void 같은_경로의_POST는_익명으로_호출할_수_없다() {
        // permitAll은 GET 한정이다 — 쓰기 메서드까지 열리는 회귀를 막는다
        assertThat(mvc.post().uri("/api/notices/active")).hasStatus(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 종료된_공지는_반환하지_않는다() {
        insertNotice("지난 공지", "이미 끝났다", null, NOW.minus(2, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.HOURS));

        assertThat(mvc.get().uri("/api/notices/active"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[]");
    }
}
