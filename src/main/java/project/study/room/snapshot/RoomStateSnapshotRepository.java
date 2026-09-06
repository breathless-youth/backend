package project.study.room.snapshot;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 룸 스냅샷 저장소 — {@code room_state_snapshot} 한 행 (BY-626).
 *
 * <p>JPA 엔티티를 두지 않는다: 종료 훅에서 한 번 쓰고 새 태스크가 한 번 읽는 jsonb 한 덩어리라
 * 영속성 컨텍스트가 필요 없고, 종료 중에는 JPA 인프라가 먼저 내려갈 수 있어 JdbcTemplate이 안전하다.
 */
@Repository
@RequiredArgsConstructor
public class RoomStateSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 있으면 덮어쓴다 — 항상 최신 종료 시점 하나만 남긴다. */
    public void save(String payloadJson, Instant savedAt) {
        jdbcTemplate.update("""
                insert into room_state_snapshot (id, payload, saved_at)
                values (1, cast(? as jsonb), ?)
                on conflict (id) do update set payload = excluded.payload, saved_at = excluded.saved_at
                """, payloadJson, savedAt.atOffset(ZoneOffset.UTC));
    }

    /** {@code savedAfter} 이후에 저장된 스냅샷만 돌려준다 — 오래된 배포의 방을 되살리지 않기 위한 신선도 가드. */
    public Optional<String> loadIfSavedAfter(Instant savedAfter) {
        List<String> rows = jdbcTemplate.query(
                "select payload::text from room_state_snapshot where id = 1 and saved_at > ?",
                (rs, i) -> rs.getString(1),
                savedAfter.atOffset(ZoneOffset.UTC));
        return rows.stream().findFirst();
    }

    /** 소비했거나 남길 방이 없을 때 지운다 — 다음 재시작이 낡은 스냅샷을 읽지 않게. */
    public void clear() {
        jdbcTemplate.update("delete from room_state_snapshot where id = 1");
    }
}
