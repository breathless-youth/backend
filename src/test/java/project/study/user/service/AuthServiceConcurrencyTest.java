package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.repository.UserRepository;

/**
 * 발급·회전·전량 폐기가 유저 행 잠금으로 직렬화되는지 실제 PostgreSQL에서 확인한다 (ADR-0019, Codex P1).
 * 잠금이 없으면 재사용 감지의 전량 폐기가 동시에 커밋 중인 회전의 새 토큰을 놓친다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthServiceConcurrencyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 다른_트랜잭션이_유저_행을_잠근_동안_refresh는_기다렸다가_잠금이_풀리면_완료된다() throws Exception {
        long userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('DEVICE', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "lock-" + UUID.randomUUID());
        AuthService.TokenPair tokens = authService.issueTokensRevokingExisting(userId);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 회전 중인 다른 요청을 흉내 낸다 — 유저 행을 잡고 release까지 트랜잭션을 열어 둔다
            Future<?> holder =
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        userRepository.findByIdForUpdate(userId);
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<TokenResponse> refresh =
                    pool.submit(() -> authService.refresh(new RefreshRequest(tokens.refreshToken())));
            // 잠금이 살아 있는 동안은 끝나지 않는다
            assertThatThrownBy(() -> refresh.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(refresh.get(10, TimeUnit.SECONDS).accessToken()).isNotBlank();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
