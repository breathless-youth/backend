package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 같은 소셜 ID로 두 기기가 동시에 전환하는 경쟁을 정해진 순서로 재현한다. a의 refresh 행을 잡아 a의 전환을 "users 행은 바꿨지만 커밋 전"에
 * 세우고, 그동안 b도 소셜 ID가 비었다고 보고 전환하다 unique 인덱스에서 a를 기다리게 한다. 풀면 a가 커밋하고, b는 위반으로 져서 새
 * 트랜잭션의 병합 경로로 다시 한다.
 */
final class ConversionRace {

    record Results(MvcTestResult first, MvcTestResult second) {}

    private ConversionRace() {}

    /** first는 a의 link, second는 b의 link다. aUserId는 a의 userId(잠글 refresh 행의 주인). */
    static Results run(
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            long aUserId,
            Callable<MvcTestResult> first,
            Callable<MvcTestResult> second)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            AtomicLong holderPid = new AtomicLong();
            Future<?> holder =
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        holderPid.set(jdbc.sql("SELECT pg_backend_pid()")
                                .query(Long.class)
                                .single());
                        jdbc.sql("SELECT id FROM refresh_token WHERE user_id = :u FOR UPDATE")
                                .param("u", aUserId)
                                .query()
                                .listOfRows();
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcTestResult> firstResult = pool.submit(first);
            long firstPid = awaitBlockedBy(jdbc, holderPid.get()); // a가 holder를 기다린다
            Future<MvcTestResult> secondResult = pool.submit(second);
            awaitBlockedBy(jdbc, firstPid); // b가 unique 인덱스에서 a를 기다린다
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return new Results(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // blocker를 기다리는 백엔드가 생길 때까지 기다려 그 pid를 돌려준다. 이 테스트가 잡은 잠금에서 시작한 사슬만 보므로, 다른 작업의
    // 잠금 대기가 있어도 일찍 풀지 않는다
    private static long awaitBlockedBy(JdbcClient jdbc, long blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            List<Long> blocked = jdbc.sql(
                            "SELECT pid FROM pg_stat_activity WHERE :blocker = ANY(pg_blocking_pids(pid))")
                    .param("blocker", (int) blockerPid)
                    .query(Long.class)
                    .list();
            if (!blocked.isEmpty()) {
                return blocked.get(0);
            }
            assertThat(System.nanoTime()).as("pid %d를 기다리는 백엔드", blockerPid).isLessThan(deadline);
            Thread.sleep(20);
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
