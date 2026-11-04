package project.study.group.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 멤버 공부 시작 알림 테스트가 상황을 세우는 도구 (BY-1124) — 그룹 공부방 자리와 알림 기록을 SQL로 바로 만들고 읽는다. 만든 방은
 * {@link #closeRooms}로 치워, 같은 컨텍스트의 다른 테스트가 열린 방을 전부 읽을 때 새지 않게 한다.
 */
public class StartAlertFixture {

    private final JdbcClient jdbc;
    private final GroupProbe probe;
    private final List<Long> roomIds = new ArrayList<>();

    public StartAlertFixture(JdbcClient jdbc, GroupProbe probe) {
        this.jdbc = jdbc;
        this.probe = probe;
    }

    /**
     * 이 그룹의 열린 공부방에 자리를 만든다. 방이 없으면 연다.
     *
     * @param confirmed 구독까지 마쳐 확정된 자리인가 — false면 예약만 한 자리
     * @param disconnected 연결이 끊겨 유예 중인가
     */
    public void seatInGroupRoom(long groupId, long userId, boolean confirmed, boolean disconnected) {
        long roomId = jdbc.sql("select id from rooms where group_id = :groupId and closed_at is null")
                .param("groupId", groupId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> openRoom(groupId));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                        insert into room_participations
                            (room_id, user_id, reserved_at, stomp_confirmed, joined_at, disconnected_at)
                        values (:roomId, :userId, :now, :confirmed, :joinedAt, :disconnectedAt)""")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("now", now)
                .param("confirmed", confirmed)
                .param("joinedAt", confirmed ? now : null)
                .param("disconnectedAt", disconnected ? now : null)
                .update();
    }

    private long openRoom(long groupId) {
        long roomId = jdbc.sql("insert into rooms (group_id, created_by, created_at) values "
                        + "(:groupId, :createdBy, now()) returning id")
                .param("groupId", groupId)
                .param("createdBy", probe.group(groupId).orElseThrow().ownerId())
                .query(Long.class)
                .single();
        roomIds.add(roomId);
        return roomId;
    }

    /** 이 사람이 자리를 비운 것으로 표시한다. */
    public void leaveSeat(long userId) {
        jdbc.sql("update room_participations set left_at = now(), leave_reason = 'EXPLICIT' "
                        + "where user_id = :userId and left_at is null")
                .param("userId", userId)
                .update();
    }

    /** 이 테스트가 만든 방과 자리를 지운다. */
    public void closeRooms() {
        for (long roomId : roomIds) {
            jdbc.sql("delete from room_participations where room_id = :id")
                    .param("id", roomId)
                    .update();
            jdbc.sql("delete from rooms where id = :id").param("id", roomId).update();
        }
        roomIds.clear();
    }

    /** (받는 사람, 그룹)의 마지막으로 보낸 때 — 기록이 없으면 null. */
    public Instant lastSentAt(long receiverId, long groupId) {
        return jdbc.sql("select last_sent_at from group_study_alert where receiver_id = :r and group_id = :g")
                .param("r", receiverId)
                .param("g", groupId)
                .query((rs, n) -> rs.getTimestamp("last_sent_at").toInstant())
                .optional()
                .orElse(null);
    }

    public void clearAlerts(Collection<Long> receiverIds) {
        if (receiverIds.isEmpty()) {
            return;
        }
        jdbc.sql("delete from group_study_alert where receiver_id in (:ids)")
                .param("ids", receiverIds)
                .update();
    }
}
