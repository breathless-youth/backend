package project.study.dday;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.dday.dto.DdayRequest;
import project.study.dday.service.DdayService;

/** 홈 D-Day API — upsert·조회·멱등 삭제·검증·유저 격리. 오늘 경계는 DdayServiceTest가 고정 시계로 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DdayApiTest {

    private static final String URI = "/api/dday";

    /** 구 앱 대응이 없는 새 경로라 기본버전(1)에 매핑돼 있다 — asUser가 붙이는 2를 덮어써야 라우팅된다. */
    private static final String DDAY_API_VERSION = "1";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DdayService ddayService;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = insertUser();
    }

    private Long insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "tester-" + UUID.randomUUID());
    }

    private MvcTestResult put(Long asUserId, String body) {
        return mvc.put()
                .uri(URI)
                .header(ApiVersionConfig.HEADER, DDAY_API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(asUser(asUserId))
                .exchange();
    }

    private MvcTestResult get(Long asUserId) {
        return mvc.get()
                .uri(URI)
                .header(ApiVersionConfig.HEADER, DDAY_API_VERSION)
                .with(asUser(asUserId))
                .exchange();
    }

    private MvcTestResult delete(Long asUserId) {
        return mvc.delete()
                .uri(URI)
                .header(ApiVersionConfig.HEADER, DDAY_API_VERSION)
                .with(asUser(asUserId))
                .exchange();
    }

    private long rowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM user_dday WHERE user_id = ?", Long.class, userId);
    }

    @Test
    void 처음_저장하면_만들어지고_제목은_공백을_잘라_조회된다() {
        assertThat(put(userId, "{\"title\": \" 2027 수능 \", \"targetDate\": \"2099-11-18\"}"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> assertThat(v).isEqualTo("2027 수능"))
                .hasPathSatisfying("$.targetDate", v -> assertThat(v).isEqualTo("2099-11-18"));

        assertThat(get(userId))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> assertThat(v).isEqualTo("2027 수능"));
    }

    @Test
    void 설정한_것이_없으면_조회는_204다() {
        assertThat(get(userId)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 다시_저장하면_덮어쓰고_행은_하나만_남는다() {
        put(userId, "{\"title\": \"수능\", \"targetDate\": \"2099-11-18\"}");
        put(userId, "{\"title\": \"토익\", \"targetDate\": \"2099-01-01\"}");

        assertThat(get(userId))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> assertThat(v).isEqualTo("토익"))
                .hasPathSatisfying("$.targetDate", v -> assertThat(v).isEqualTo("2099-01-01"));
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void 과거_날짜는_400이다() {
        assertThat(put(userId, "{\"title\": \"수능\", \"targetDate\": \"2000-01-01\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.message", v -> assertThat(v).asString().contains("오늘 이후"));
        assertThat(rowCount()).isZero();
    }

    @Test
    void 제목_11자와_공백_제목은_400이다() {
        assertThat(put(userId, "{\"title\": \"가나다라마바사아자차카\", \"targetDate\": \"2099-11-18\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.message", v -> assertThat(v).asString().contains("10자"));
        assertThat(put(userId, "{\"title\": \"   \", \"targetDate\": \"2099-11-18\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 삭제는_멱등이고_삭제_뒤_조회는_204다() {
        put(userId, "{\"title\": \"수능\", \"targetDate\": \"2099-11-18\"}");

        assertThat(delete(userId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(get(userId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(userId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(rowCount()).isZero();
    }

    @Test
    void 같은_유저의_동시_첫_저장은_둘_다_성공하고_행은_하나다() throws Exception {
        int requests = 2;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<?>> results = List.of(
                    pool.submit(() -> saveWhenReleased(ready, go, "수능")),
                    pool.submit(() -> saveWhenReleased(ready, go, "토익")));
            ready.await();
            go.countDown();
            for (Future<?> result : results) {
                result.get(); // 어느 쪽이든 예외 없이 끝나야 한다 — 조회-후-저장이면 한쪽이 유니크 충돌로 죽는다
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowCount()).isEqualTo(1);
        assertThat(get(userId)).hasStatus(HttpStatus.OK);
    }

    private void saveWhenReleased(CountDownLatch ready, CountDownLatch go, String title) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        ddayService.save(userId, new DdayRequest(title, LocalDate.of(2099, 11, 18)));
    }

    @Test
    void 다른_유저의_D_Day는_보이지_않는다() {
        put(userId, "{\"title\": \"수능\", \"targetDate\": \"2099-11-18\"}");
        Long other = insertUser();

        assertThat(get(other)).hasStatus(HttpStatus.NO_CONTENT);
    }
}
