package project.study.group.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 그룹 공부방 테스트 전용 DB 프로브 (BY-1124) — 방 행과 자리 행을 SQL로 바로 읽는다. 운영 코드를 거치지 않아 서비스가 남긴 값과 비교한다. */
public class GroupRoomProbe {

    public record RoomRow(long id, String inviteCode, Long groupId, Instant closedAt) {}

    public record SeatRow(
            long id,
            boolean stompConfirmed,
            Instant joinedAt,
            Instant leftAt,
            String leaveReason,
            boolean cameraOn,
            boolean micOn,
            boolean onBreak) {}

    private static final RowMapper<RoomRow> ROOM = (rs, n) -> new RoomRow(
            rs.getLong("id"),
            rs.getString("invite_code"),
            rs.getObject("group_id", Long.class),
            instant(rs, "closed_at"));

    private static final RowMapper<SeatRow> SEAT = (rs, n) -> new SeatRow(
            rs.getLong("id"),
            rs.getBoolean("stomp_confirmed"),
            instant(rs, "joined_at"),
            instant(rs, "left_at"),
            rs.getString("leave_reason"),
            rs.getBoolean("camera_on"),
            rs.getBoolean("mic_on"),
            rs.getBoolean("on_break"));

    private final JdbcClient jdbc;

    public GroupRoomProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /** 이 그룹의 열린 공부방 ID. */
    public Optional<Long> openRoomOf(long groupId) {
        return jdbc.sql("select id from rooms where group_id = :groupId and closed_at is null")
                .param("groupId", groupId)
                .query(Long.class)
                .optional();
    }

    public RoomRow room(long roomId) {
        return jdbc.sql("select id, invite_code, group_id, closed_at from rooms where id = :id")
                .param("id", roomId)
                .query(ROOM)
                .single();
    }

    /** (방, 사람)의 가장 최근 자리 행 — 나간 자리도 포함. 확정 전 예약이 지워졌으면 비어 있다. */
    public Optional<SeatRow> seat(long roomId, long userId) {
        return jdbc.sql("select * from room_participations where room_id = :roomId and user_id = :userId "
                        + "order by id desc limit 1")
                .param("roomId", roomId)
                .param("userId", userId)
                .query(SEAT)
                .optional();
    }

    /** 이 사람이 지금 자리를 가진 방. */
    public Optional<Long> liveRoomOf(long userId) {
        return jdbc.sql("select room_id from room_participations where user_id = :userId and left_at is null")
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }
}
