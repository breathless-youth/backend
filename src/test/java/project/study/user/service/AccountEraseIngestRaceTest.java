package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.metrics.amplitude.AmplitudeTestAggregates;
import project.study.metrics.amplitude.AmplitudeWindowWriter;
import project.study.metrics.amplitude.ExportWindow;
import project.study.metrics.amplitude.WindowAggregate;
import project.study.user.entity.Provider;
import project.study.user.oauth.OAuthUserInfo;

/**
 * 탈퇴·병합과 Amplitude 적재가 같은 사람을 동시에 다룰 때, 어느 쪽이 먼저 users 행을 잡든 둘 다 끝나고 지운 사람의 적재본이 남지 않는지 본다.
 * 한쪽 트랜잭션을 커밋 전에 붙잡아 두고 다른 쪽이 잠금을 기다리는 것을 확인한 뒤 놓아준다. 테스트 바깥 트랜잭션은 두지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountEraseIngestRaceTest {

    private static final LocalDate DAY = LocalDate.of(2031, 1, 1);
    private static final Instant SEEN = Instant.parse("2031-01-01T01:00:00Z");

    @Autowired
    private AccountEraser accountEraser;

    @Autowired
    private AccountLinker accountLinker;

    @Autowired
    private AmplitudeWindowWriter writer;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        Long[] ids = created.toArray(Long[]::new);
        jdbc.update("DELETE FROM amplitude_daily_event WHERE user_id = ANY(?)", (Object) ids);
        jdbc.update("DELETE FROM amplitude_user_attribute WHERE user_id = ANY(?)", (Object) ids);
        jdbc.update("DELETE FROM amplitude_export_window WHERE window_date = ?", DAY);
    }

    @Test
    void 탈퇴가_먼저_잡으면_적재는_기다렸다가_아무것도_쓰지_않는다() throws Exception {
        long user = insertUser(Provider.DEVICE, "device-" + UUID.randomUUID());
        seedAmplitude(user);

        Held erasing = hold(() -> accountEraser.erase(user));
        Future<Boolean> ingesting = pool.submit(() -> writer.write(new ExportWindow(DAY), aggregate(user)));
        awaitLockWait();
        erasing.release();

        erasing.result().get(10, TimeUnit.SECONDS);
        assertThat(ingesting.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(amplitudeRows(user)).isZero();
        assertThat(userExists(user)).isFalse();
    }

    @Test
    void 적재가_먼저_잡으면_탈퇴는_기다렸다가_새로_쓴_행까지_지운다() throws Exception {
        long user = insertUser(Provider.DEVICE, "device-" + UUID.randomUUID());

        Held ingesting = hold(() -> writer.write(new ExportWindow(DAY), aggregate(user)));
        Future<?> erasing = pool.submit(() -> accountEraser.erase(user));
        awaitLockWait();
        ingesting.release();

        assertThat(ingesting.result().get(10, TimeUnit.SECONDS)).isEqualTo(true);
        erasing.get(10, TimeUnit.SECONDS);
        assertThat(amplitudeRows(user)).isZero();
        assertThat(userExists(user)).isFalse();
    }

    @Test
    void 병합이_먼저_잡으면_적재는_옮기는_쪽을_쓰지_않고_남는_쪽만_쓴다() throws Exception {
        long source = insertUser(Provider.DEVICE, "device-" + UUID.randomUUID());
        String sub = "google-" + UUID.randomUUID();
        long target = insertUser(Provider.GOOGLE, sub);
        seedAmplitude(source);
        OAuthUserInfo social = new OAuthUserInfo(
                Provider.GOOGLE, sub, null, null, Instant.now().plusSeconds(3600));
        String idTokenHash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");

        Held merging = hold(() -> accountLinker.link(source, social, idTokenHash));
        Future<Boolean> ingesting = pool.submit(
                () -> writer.write(new ExportWindow(DAY), AmplitudeTestAggregates.launched(DAY, SEEN, source, target)));
        awaitLockWait();
        merging.release();

        merging.result().get(10, TimeUnit.SECONDS);
        assertThat(ingesting.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(amplitudeRows(source)).isZero();
        assertThat(amplitudeRows(target)).isEqualTo(2);
        assertThat(userExists(source)).isFalse();
    }

    /** 작업을 한 트랜잭션에서 돌리고, 끝난 뒤 커밋하지 않고 release()까지 기다린다. */
    private Held hold(Callable<?> work) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Object> result = pool.submit(() -> transactions.execute(status -> {
            try {
                Object value = work.call();
                done.countDown();
                release.await(10, TimeUnit.SECONDS);
                return value;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        return new Held(result, release);
    }

    private record Held(Future<Object> result, CountDownLatch releaser) {
        void release() {
            releaser.countDown();
        }
    }

    // 다른 쪽이 정말 잠금을 기다리는 것까지 확인해야 겹친 상황을 시험한 것이다
    private void awaitLockWait() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("다른 트랜잭션이 잠금을 기다리지 않았다");
    }

    private long insertUser(Provider provider, String providerUserId) {
        long id = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES (?, ?) RETURNING id",
                Long.class,
                provider.name(),
                providerUserId);
        created.add(id);
        return id;
    }

    private void seedAmplitude(long userId) {
        jdbc.update(
                "INSERT INTO amplitude_daily_event (user_id, event_date, event_key, count) VALUES (?, ?, 'app_launched', 1)",
                userId,
                DAY);
        jdbc.update(
                "INSERT INTO amplitude_user_attribute (user_id, first_seen_at, last_seen_at) VALUES (?, now(), now())",
                userId);
    }

    private static WindowAggregate aggregate(long userId) {
        return AmplitudeTestAggregates.launched(DAY, SEEN, userId);
    }

    private long amplitudeRows(long userId) {
        return jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM amplitude_daily_event WHERE user_id = ?)"
                        + " + (SELECT count(*) FROM amplitude_user_attribute WHERE user_id = ?)",
                Long.class,
                userId,
                userId);
    }

    private boolean userExists(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, userId) == 1;
    }
}
