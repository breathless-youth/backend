package project.study.room.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.room.entity.CloseReason;

/**
 * rooms 테이블의 쓰기·락 경로. 전부 네이티브 SQL + record — JPA 1차 캐시가 낡은 값을 돌려주는 문제를 피한다.
 *
 * <p>advisory lock 키 공간: 유저 락은 userId 그대로, 초대코드 락은 2^40 + hashtext(code). 두 종류가 같은
 * 키에서 충돌하지 않게 분리한다. 락 순서는 스펙 §2 — 유저 → 방 행 → 참가자 행 → 코드(닫을 때만, 항상 마지막).
 *
 * <p>방은 초대코드 방(invite_code만)과 그룹 공부방(group_id만, BY-1124) 두 종류다. 코드로 찾는 문장은 코드가 NULL인 그룹 공부방을
 * 만나지 않고(= NULL은 어떤 코드와도 같지 않다), 그룹 공부방은 그룹마다 열린 방이 하나다(rooms_open_group_uidx).
 */
@Repository
@RequiredArgsConstructor
public class RoomRepository {

    private static final long CODE_LOCK_OFFSET = 1L << 40;

    /** inviteCode와 groupId 중 정확히 하나만 있다 — 초대코드 방이면 코드, 그룹 공부방이면 그룹. */
    public record RoomRow(
            Long id,
            String inviteCode,
            Long createdBy,
            Instant createdAt,
            Instant closedAt,
            String closeReason,
            Long groupId) {

        /** 초대코드 방 행. */
        public RoomRow(
                Long id, String inviteCode, Long createdBy, Instant createdAt, Instant closedAt, String closeReason) {
            this(id, inviteCode, createdBy, createdAt, closedAt, closeReason, null);
        }

        public boolean isOpen() {
            return closedAt == null;
        }
    }

    private static final String COLUMNS = "id, invite_code, created_by, created_at, closed_at, close_reason, group_id";

    private static final RowMapper<RoomRow> ROW = (rs, n) -> new RoomRow(
            rs.getLong("id"),
            rs.getString("invite_code"),
            rs.getLong("created_by"),
            instant(rs, "created_at"),
            instant(rs, "closed_at"),
            rs.getString("close_reason"),
            rs.getObject("group_id", Long.class));

    private final JdbcClient jdbc;

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** 같은 유저의 동시 join 직렬화 (트랜잭션 끝까지 유지). */
    public void lockUser(Long userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", userId)
                .query()
                .listOfRows();
    }

    /** 같은 코드의 발급과 닫힘을 직렬화한다 — 묘비 기간 재발급 금지의 실제 보장 주체. */
    public void lockInviteCode(String inviteCode) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:offset + hashtext(:code))")
                .param("offset", CODE_LOCK_OFFSET)
                .param("code", inviteCode)
                .query()
                .listOfRows();
    }

    /**
     * 코드가 비어 있을 때만 방을 만든다. 열린 방 충돌은 부분 유니크 인덱스의 ON CONFLICT가, 묘비(닫힌 지
     * 10분 안) 충돌은 NOT EXISTS가 거른다. 코드 락을 먼저 잡아 같은 순간의 닫힘과 직렬화한다.
     */
    public Optional<Long> insertIfCodeFree(String inviteCode, Long createdBy, Instant now, Instant tombstoneCutoff) {
        lockInviteCode(inviteCode);
        return jdbc.sql("""
                        INSERT INTO rooms (invite_code, created_by, created_at)
                        SELECT :code, :createdBy, :now
                        WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE invite_code = :code AND closed_at > :cutoff)
                        ON CONFLICT (invite_code) WHERE closed_at IS NULL DO NOTHING
                        RETURNING id""")
                .param("code", inviteCode)
                .param("createdBy", createdBy)
                .param("now", ts(now))
                .param("cutoff", ts(tombstoneCutoff))
                .query(Long.class)
                .optional();
    }

    public Optional<RoomRow> findLatestByCode(String inviteCode) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM rooms WHERE invite_code = :code ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("code", inviteCode)
                .query(ROW)
                .optional();
    }

    /**
     * 그룹의 열린 공부방 ID — 없으면 연다. 먼저 읽고, 없을 때만 {@code ON CONFLICT DO NOTHING}으로 넣은 뒤 다시 읽는다. 같은 순간 처음 들어오는
     * 두 멤버는 열린 방 unique에서 만나 하나는 넣고 하나는 그 커밋을 기다렸다가 같은 방을 읽는다.
     *
     * <p>비어 있으면 넣으려던 순간 열린 방이 있었는데 다시 읽기 전에 닫혔다는 뜻이다 — 호출자가 트랜잭션을 새로 열어 다시 한다. 이 문장은 방 행을
     * 잠그지 않는다(호출자가 다른 방과 함께 id 순으로 잠근다). 넣은 행은 커밋 전까지 같은 그룹의 방을 열려는 다른 멤버만 기다리게 하고, 그 멤버는 이
     * 문장을 어떤 방 행도 쥐기 전에 부르므로, 넣은 뒤 더 작은 id의 방을 잠가도 거꾸로 기다리는 쪽이 없다.
     */
    public Optional<Long> openGroupRoom(long groupId, long createdBy, Instant now) {
        Optional<Long> open = findOpenIdByGroup(groupId);
        if (open.isPresent()) {
            return open;
        }
        Optional<Long> inserted = jdbc.sql("""
                        INSERT INTO rooms (group_id, created_by, created_at) VALUES (:groupId, :createdBy, :now)
                        ON CONFLICT (group_id) WHERE closed_at IS NULL AND group_id IS NOT NULL DO NOTHING
                        RETURNING id""")
                .param("groupId", groupId)
                .param("createdBy", createdBy)
                .param("now", ts(now))
                .query(Long.class)
                .optional();
        return inserted.isPresent() ? inserted : findOpenIdByGroup(groupId);
    }

    private Optional<Long> findOpenIdByGroup(long groupId) {
        return jdbc.sql("SELECT id FROM rooms WHERE group_id = :groupId AND closed_at IS NULL")
                .param("groupId", groupId)
                .query(Long.class)
                .optional();
    }

    public Optional<RoomRow> findOpenByGroup(long groupId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE group_id = :groupId AND closed_at IS NULL")
                .param("groupId", groupId)
                .query(ROW)
                .optional();
    }

    /** 그룹의 열린 공부방 행을 잠근다. 기다리는 사이 닫혔으면 다시 확인한 조건에서 빠져 비어 있다. */
    public Optional<RoomRow> lockOpenByGroup(long groupId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE group_id = :groupId AND closed_at IS NULL FOR UPDATE")
                .param("groupId", groupId)
                .query(ROW)
                .optional();
    }

    public Optional<RoomRow> lockById(Long roomId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE id = :id FOR UPDATE")
                .param("id", roomId)
                .query(ROW)
                .optional();
    }

    /** 여러 방을 id 오름차순으로 잠근다 — join의 방 전환에서 데드락을 피하는 고정 순서. */
    public List<RoomRow> lockByIds(Collection<Long> roomIds) {
        if (roomIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM rooms WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", roomIds)
                .query(ROW)
                .list();
    }

    public boolean isOpen(Long roomId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :id AND closed_at IS NULL)")
                .param("id", roomId)
                .query(Boolean.class)
                .single();
    }

    /**
     * 라이브 참가자가 없을 때만 닫는다. 호출자가 방 행 락을 쥔 상태여야 하고, 코드 락은 여기서 마지막으로 잡는다. 그룹 공부방은 코드가 없어 코드
     * 락을 건너뛴다 — 코드 락은 같은 코드의 재발급과 닫힘을 직렬화하려는 것이고, 그룹 공부방의 "다음 방 열기"는 열린 방 unique가 막는다.
     */
    public boolean closeIfEmpty(Long roomId, String inviteCode, CloseReason reason, Instant now) {
        if (inviteCode != null) {
            lockInviteCode(inviteCode);
        }
        int updated = jdbc.sql("""
                        UPDATE rooms SET closed_at = :now, close_reason = :reason
                         WHERE id = :id AND closed_at IS NULL
                           AND NOT EXISTS (SELECT 1 FROM room_participations WHERE room_id = :id AND left_at IS NULL)""")
                .param("now", ts(now))
                .param("reason", reason.name())
                .param("id", roomId)
                .update();
        return updated == 1;
    }

    public List<RoomRow> findEmptyOpenRoomsCreatedBefore(Instant deadline) {
        return jdbc.sql("""
                        SELECT r.id, r.invite_code, r.created_by, r.created_at, r.closed_at, r.close_reason, r.group_id
                          FROM rooms r
                         WHERE r.closed_at IS NULL AND r.created_at < :deadline
                           AND NOT EXISTS (SELECT 1 FROM room_participations p WHERE p.room_id = r.id AND p.left_at IS NULL)
                         ORDER BY r.id""").param("deadline", ts(deadline)).query(ROW).list();
    }
}
