package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.ConflictException;
import project.study.room.support.RoomProbe;
import project.study.room.support.RoomTestBase;

/** 전역 락 없이도 DB 제약·방 행 락·코드 락이 불변식을 지키는지 — 실제 스레드로 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomConcurrencyTest extends RoomTestBase {

    @Autowired
    private TransactionTemplate tx;

    private static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 스무_명이_동시에_들어와도_정확히_여섯_명만_자리를_얻는다() throws Exception {
        String code = createRoom();
        long roomId = rooms.findLatestByCode(code).orElseThrow().id();
        List<Callable<Boolean>> joins = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long userId = user();
            joins.add(() -> {
                try {
                    join(userId, code);
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            });
        }

        List<Boolean> results = runAll(joins);

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(6);
        assertThat(probe.countLive(roomId)).isEqualTo(6);
    }

    @Test
    void 같은_유저가_두_방에_동시에_들어가도_라이브_자리는_하나다() throws Exception {
        String codeA = createRoom();
        String codeB = createRoom();
        long userId = user();

        runAll(List.<Callable<Boolean>>of(() -> join(userId, codeA) != null, () -> join(userId, codeB) != null));

        List<RoomProbe.Participation> rows = probe.participationsOfUser(userId);
        assertThat(rows.stream().filter(r -> r.leftAt() == null)).hasSize(1);
    }

    @Test
    void 방이_닫히는_순간에도_같은_코드는_묘비_기간_안에_재발급되지_않는다() throws Exception {
        for (int round = 0; round < 20; round++) {
            String code = createRoom();
            long userId = user();
            long roomId = join(userId, code).response().roomId();

            List<Boolean> results = runAll(List.<Callable<Boolean>>of(
                    () -> roomService.leave(roomId, userId).removed(),
                    () -> tx.execute(s -> rooms.insertIfCodeFree(
                                    code, owner, Instant.now(), Instant.now().minusSeconds(600)))
                            .isPresent()));

            assertThat(results.getFirst()).as("퇴장은 성공").isTrue();
            assertThat(results.get(1)).as("닫힘과 겹친 발급은 코드 락 덕에 묘비를 보고 실패").isFalse();
            assertThat(rooms.isOpen(roomId)).isFalse();
        }
    }
}
