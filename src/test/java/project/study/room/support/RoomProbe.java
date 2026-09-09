package project.study.room.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 룸 통합 테스트 전용 DB 프로브 — 유저 생성, 행 조회, 시각 조작. 운영 코드가 아니라 SQL을 그대로 쓴다. */
public class RoomProbe {

    public record Participation(
            Long id,
            Long roomId,
            Long userId,
            boolean stompConfirmed,
            String stompSessionId,
            String taskId,
            Instant disconnectedAt,
            Instant joinedAt,
            Instant leftAt,
            String leaveReason,
            int focusSec,
            boolean cameraOn,
            String focusState) {}

    public record RoomRow(Long id, String inviteCode, Instant closedAt, String closeReason) {}

    private static final RowMapper<Participation> PARTICIPATION = (rs, n) -> new Participation(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getLong("user_id"),
            rs.getBoolean("stomp_confirmed"),
            rs.getString("stomp_session_id"),
            rs.getString("task_id"),
            instant(rs, "disconnected_at"),
            instant(rs, "joined_at"),
            instant(rs, "left_at"),
            rs.getString("leave_reason"),
            rs.getInt("focus_sec"),
            rs.getBoolean("camera_on"),
            rs.getString("focus_state"));

    private static final RowMapper<RoomRow> ROOM = (rs, n) -> new RoomRow(
            rs.getLong("id"), rs.getString("invite_code"), instant(rs, "closed_at"), rs.getString("close_reason"));

    private final JdbcClient jdbc;

    public RoomProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public long insertUser() {
        return jdbc.sql("insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', :device, now(), now()) returning id")
                .param("device", UUID.randomUUID().toString())
                .query(Long.class)
                .single();
    }

    public Optional<RoomRow> room(long roomId) {
        return jdbc.sql("select id, invite_code, closed_at, close_reason from rooms where id = :id")
                .param("id", roomId)
                .query(ROOM)
                .optional();
    }

    /** (방, 유저)의 가장 최근 행 — 이력까지 포함. */
    public Optional<Participation> participation(long roomId, long userId) {
        return jdbc.sql("select * from room_participations where room_id = :roomId and user_id = :userId "
                        + "order by id desc limit 1")
                .param("roomId", roomId)
                .param("userId", userId)
                .query(PARTICIPATION)
                .optional();
    }

    public List<Participation> participationsOfUser(long userId) {
        return jdbc.sql("select * from room_participations where user_id = :userId order by id")
                .param("userId", userId)
                .query(PARTICIPATION)
                .list();
    }

    public int countLive(long roomId) {
        return jdbc.sql("select count(*) from room_participations where room_id = :roomId and left_at is null")
                .param("roomId", roomId)
                .query(Integer.class)
                .single();
    }

    public void closeRoomAt(long roomId, Instant closedAt) {
        jdbc.sql("update rooms set closed_at = :at, close_reason = 'LAST_LEFT' where id = :id")
                .param("at", Timestamp.from(closedAt))
                .param("id", roomId)
                .update();
    }

    public void setReservedAt(long roomId, long userId, Instant at) {
        jdbc.sql("update room_participations set reserved_at = :at "
                        + "where room_id = :roomId and user_id = :userId and left_at is null")
                .param("at", Timestamp.from(at))
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    public void setDisconnectedAt(long roomId, long userId, Instant at) {
        jdbc.sql("update room_participations set disconnected_at = :at "
                        + "where room_id = :roomId and user_id = :userId and left_at is null")
                .param("at", Timestamp.from(at))
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    public void setHeartbeat(String taskId, Instant at) {
        jdbc.sql("update live_task set heartbeat_at = :at where task_id = :id")
                .param("at", Timestamp.from(at))
                .param("id", taskId)
                .update();
    }

    public void setReclaimed(String taskId, Instant at) {
        jdbc.sql("update live_task set reclaimed_at = :at where task_id = :id")
                .param("at", at == null ? null : Timestamp.from(at))
                .param("id", taskId)
                .update();
    }

    public Optional<Instant> heartbeatAt(String taskId) {
        return jdbc.sql("select heartbeat_at from live_task where task_id = :id")
                .param("id", taskId)
                .query((rs, n) -> instant(rs, "heartbeat_at"))
                .optional();
    }

    public Optional<Instant> reclaimedAt(String taskId) {
        return jdbc.sql("select reclaimed_at from live_task where task_id = :id")
                .param("id", taskId)
                .query((rs, n) -> Optional.ofNullable(instant(rs, "reclaimed_at")))
                .optional()
                .flatMap(o -> o);
    }
}
