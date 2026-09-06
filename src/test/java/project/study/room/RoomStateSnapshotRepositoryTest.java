package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import project.study.TestcontainersConfiguration;
import project.study.room.service.RoomIdAllocator;
import project.study.room.snapshot.RoomStateSnapshotRepository;

/** BY-626 — 스냅샷 한 행의 저장·신선도 조회·삭제와 방 ID 시퀀스가 실제 PostgreSQL에서 동작하는지. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RoomStateSnapshotRepositoryTest {

    @Autowired
    private RoomStateSnapshotRepository repository;

    @Autowired
    private RoomIdAllocator roomIdAllocator;

    @BeforeEach
    void clean() {
        repository.clear();
    }

    @Test
    void 저장하면_그_시각_이전_기준으로_읽히고_이후_기준으로는_읽히지_않는다() {
        Instant savedAt = Instant.parse("2026-09-06T10:00:00Z");
        repository.save("{\"takenAt\":\"2026-09-06T10:00:00Z\",\"rooms\":[],\"closedCodes\":{}}", savedAt);

        assertThat(repository.loadIfSavedAfter(savedAt.minusSeconds(120))).isPresent();
        assertThat(repository.loadIfSavedAfter(savedAt)).isEmpty(); // 경계는 초과만 신선
        assertThat(repository.loadIfSavedAfter(savedAt.plusSeconds(1))).isEmpty();
    }

    @Test
    void 두_번_저장하면_한_행이_최신으로_덮인다() {
        Instant first = Instant.parse("2026-09-06T10:00:00Z");
        Instant second = first.plusSeconds(60);
        repository.save("{\"rooms\":[],\"closedCodes\":{},\"takenAt\":\"a\"}", first);
        repository.save("{\"rooms\":[],\"closedCodes\":{},\"takenAt\":\"b\"}", second);

        assertThat(repository.loadIfSavedAfter(first))
                .hasValueSatisfying(json -> assertThat(json).contains("\"b\""));
    }

    @Test
    void 지우면_읽히지_않는다() {
        repository.save("{\"rooms\":[],\"closedCodes\":{}}", Instant.now());
        repository.clear();

        assertThat(repository.loadIfSavedAfter(Instant.EPOCH)).isEmpty();
    }

    @Test
    void 방_ID는_시퀀스에서_단조_증가로_발급된다() {
        long a = roomIdAllocator.next();
        long b = roomIdAllocator.next();

        assertThat(b).isGreaterThan(a);
    }
}
