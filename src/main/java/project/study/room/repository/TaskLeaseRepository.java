package project.study.room.repository;

import static project.study.room.repository.RoomRepository.instant;
import static project.study.room.repository.RoomRepository.ts;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * live_task — 태스크 리스. 회수(reclaim)와 heartbeat가 같은 행을 UPDATE로 잠그므로 둘 중 하나만 이기고,
 * 진 쪽은 반드시 상대의 결과를 본다 (스펙 §2.10-1·§3). heartbeat 전용 커넥션에서는 TaskLease가 이 클래스를
 * 직접 생성해 쓰고, cleanup 스윕은 기본 JdbcClient 빈으로 만든 인스턴스를 쓴다.
 */
@Repository
public class TaskLeaseRepository {

    /** rowExists=false(행이 사라짐) 또는 reclaimedAt이 있으면 다른 태스크가 나를 죽었다고 처리한 것 → 펜싱. */
    public record Heartbeat(boolean rowExists, Instant reclaimedAt) {
        public boolean fenced() {
            return !rowExists || reclaimedAt != null;
        }
    }

    private final JdbcClient jdbc;

    public TaskLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 기동 등록과 펜싱 뒤 되살리기 — reclaimed_at을 비운다. */
    public void register(String taskId, Instant now) {
        jdbc.sql(
                        "INSERT INTO live_task (task_id, heartbeat_at, reclaimed_at) VALUES (:id, :now, NULL) "
                                + "ON CONFLICT (task_id) DO UPDATE SET heartbeat_at = EXCLUDED.heartbeat_at, reclaimed_at = NULL")
                .param("id", taskId)
                .param("now", ts(now))
                .update();
    }

    public Heartbeat heartbeat(String taskId, Instant now) {
        return jdbc.sql("UPDATE live_task SET heartbeat_at = :now WHERE task_id = :id RETURNING reclaimed_at")
                .param("now", ts(now))
                .param("id", taskId)
                .query((rs, n) -> new Heartbeat(true, instant(rs, "reclaimed_at")))
                .optional()
                .orElse(new Heartbeat(false, null));
    }

    public List<String> findStaleUnreclaimed(Instant threshold) {
        return jdbc.sql("SELECT task_id FROM live_task WHERE heartbeat_at < :threshold AND reclaimed_at IS NULL "
                        + "ORDER BY task_id")
                .param("threshold", ts(threshold))
                .query(String.class)
                .list();
    }

    /** 리스 행을 잠그며 회수 표시. heartbeat가 먼저 커밋됐으면 조건이 거짓이라 false. */
    public boolean reclaim(String taskId, Instant threshold, Instant now) {
        int updated = jdbc.sql("UPDATE live_task SET reclaimed_at = :now "
                        + "WHERE task_id = :id AND heartbeat_at < :threshold AND reclaimed_at IS NULL")
                .param("now", ts(now))
                .param("id", taskId)
                .param("threshold", ts(threshold))
                .update();
        return updated == 1;
    }

    public int deleteReclaimedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM live_task WHERE reclaimed_at < :cutoff")
                .param("cutoff", ts(cutoff))
                .update();
    }
}
