package project.study.room.repository;

import static project.study.room.repository.RoomRepository.instant;
import static project.study.room.repository.RoomRepository.ts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;
import project.study.room.dto.RoomMember;
import project.study.room.entity.LeaveReason;

/**
 * room_participations의 쓰기·락·조건부 갱신 경로. 모든 갱신은 갱신 건수를 돌려주고 호출자가 0건을
 * "상황이 바뀜"으로 처리한다 (스펙 §2). 방 인원이 바뀌는 연산(insert/delete/markLeft)은 호출자가 방 행 락을
 * 쥔 채 부르고, 단일 행 조건부 갱신(confirm/markDisconnected/updateState)은 락 없이 부른다.
 */
@Repository
@RequiredArgsConstructor
public class RoomParticipationRepository {

    public record Row(
            Long id,
            Long roomId,
            Long userId,
            String nickname,
            String goal,
            String category,
            boolean cameraOn,
            String focusState,
            int focusSec,
            Instant reservedAt,
            boolean stompConfirmed,
            String stompSessionId,
            Instant sessionOpenedAt,
            String taskId,
            Instant disconnectedAt,
            Instant joinedAt,
            Instant leftAt,
            String leaveReason) {}

    public record Profile(String nickname, String goal, String category) {}

    /** cleanup 경로의 만료 조건. null이면 조건 없음(명시 퇴장·방 전환). */
    public record ExpiryWindow(Instant reservationDeadline, Instant graceDeadline) {}

    public record Candidate(Long id, Long roomId, Long userId) {}

    private static final String COLUMNS = "id, room_id, user_id, nickname, goal, category, camera_on, focus_state, "
            + "focus_sec, reserved_at, stomp_confirmed, stomp_session_id, session_opened_at, task_id, "
            + "disconnected_at, joined_at, left_at, leave_reason";

    private static final String EXPIRED =
            "((NOT stomp_confirmed AND reserved_at < :reservationDeadline) " + "OR disconnected_at < :graceDeadline)";

    private static final String MEMBER_COLUMNS =
            "user_id, nickname, goal, category, camera_on, focus_state, focus_sec, "
                    + "(disconnected_at IS NOT NULL) AS disconnected";

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getLong("room_id"),
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getString("goal"),
            rs.getString("category"),
            rs.getBoolean("camera_on"),
            rs.getString("focus_state"),
            rs.getInt("focus_sec"),
            instant(rs, "reserved_at"),
            rs.getBoolean("stomp_confirmed"),
            rs.getString("stomp_session_id"),
            instant(rs, "session_opened_at"),
            rs.getString("task_id"),
            instant(rs, "disconnected_at"),
            instant(rs, "joined_at"),
            instant(rs, "left_at"),
            rs.getString("leave_reason"));

    private static final RowMapper<RoomMember> MEMBER = (rs, n) -> new RoomMember(
            rs.getLong("user_id"),
            rs.getString("nickname"),
            rs.getString("goal"),
            rs.getString("category"),
            rs.getBoolean("camera_on"),
            rs.getString("focus_state"),
            rs.getInt("focus_sec"),
            rs.getBoolean("disconnected"));

    private static final RowMapper<Candidate> CANDIDATE =
            (rs, n) -> new Candidate(rs.getLong("id"), rs.getLong("room_id"), rs.getLong("user_id"));

    private final JdbcClient jdbc;

    public Optional<Long> findLiveRoomIdOfUser(Long userId) {
        return jdbc.sql("SELECT room_id FROM room_participations WHERE user_id = :userId AND left_at IS NULL")
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }

    public Optional<Row> lockLiveOfUser(Long userId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM room_participations WHERE user_id = :userId AND left_at IS NULL FOR UPDATE")
                .param("userId", userId)
                .query(ROW)
                .optional();
    }

    public Optional<Row> lockLive(Long roomId, Long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND user_id = :userId AND left_at IS NULL FOR UPDATE")
                .param("roomId", roomId)
                .param("userId", userId)
                .query(ROW)
                .optional();
    }

    public int countLive(Long roomId) {
        return jdbc.sql("SELECT count(*) FROM room_participations WHERE room_id = :roomId AND left_at IS NULL")
                .param("roomId", roomId)
                .query(Integer.class)
                .single();
    }

    public long insertReservation(Long roomId, Long userId, Profile profile, Instant now) {
        return jdbc.sql("INSERT INTO room_participations (room_id, user_id, nickname, goal, category, reserved_at) "
                        + "VALUES (:roomId, :userId, :nickname, :goal, :category, :now) RETURNING id")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("nickname", profile.nickname())
                .param("goal", profile.goal())
                .param("category", profile.category())
                .param("now", ts(now))
                .query(Long.class)
                .single();
    }

    /** 유예 복귀 — 끊김 상태일 때만. 예약 상태로 되돌려 30초 안에 다시 구독하게 한다. session_opened_at은 남긴다. */
    public int restoreFromGrace(Long id, Profile profile, Instant now) {
        return withProfile(
                        jdbc.sql("UPDATE room_participations SET disconnected_at = NULL, stomp_confirmed = false, "
                                + "stomp_session_id = NULL, task_id = NULL, reserved_at = :now, nickname = :nickname, "
                                + "goal = :goal, category = :category "
                                + "WHERE id = :id AND left_at IS NULL AND disconnected_at IS NOT NULL"),
                        profile)
                .param("now", ts(now))
                .param("id", id)
                .update();
    }

    /** 예약 재시도·확정 멤버의 중복 join — 예약 시각과 프로필만 갱신하고 확정·세션은 건드리지 않는다. */
    public int refreshReservation(Long id, Profile profile, Instant now) {
        return withProfile(
                        jdbc.sql("UPDATE room_participations SET reserved_at = :now, nickname = :nickname, "
                                + "goal = :goal, category = :category WHERE id = :id AND left_at IS NULL"),
                        profile)
                .param("now", ts(now))
                .param("id", id)
                .update();
    }

    private static StatementSpec withProfile(StatementSpec spec, Profile profile) {
        return spec.param("nickname", profile.nickname())
                .param("goal", profile.goal())
                .param("category", profile.category());
    }

    /** 확정된 적 없는 예약은 이력 없이 지운다. window가 있으면 만료 조건까지 한 문장에서 검사한다. */
    public int deleteUnconfirmed(Long id, ExpiryWindow window) {
        String sql = "DELETE FROM room_participations WHERE id = :id AND left_at IS NULL AND joined_at IS NULL"
                + (window == null ? "" : " AND " + EXPIRED);
        return withWindow(jdbc.sql(sql).param("id", id), window).update();
    }

    /** 확정된 참가자는 left_at·사유를 찍어 이력으로 남긴다. 두 번 불려도 두 번째는 0행. */
    public int markLeft(Long id, LeaveReason reason, Instant now, ExpiryWindow window) {
        String sql = "UPDATE room_participations SET left_at = :now, leave_reason = :reason, stomp_session_id = NULL, "
                + "task_id = NULL, disconnected_at = NULL "
                + "WHERE id = :id AND left_at IS NULL AND joined_at IS NOT NULL"
                + (window == null ? "" : " AND " + EXPIRED);
        return withWindow(jdbc.sql(sql).param("id", id).param("now", ts(now)).param("reason", reason.name()), window)
                .update();
    }

    private static StatementSpec withWindow(StatementSpec spec, ExpiryWindow window) {
        if (window == null) return spec;
        return spec.param("reservationDeadline", ts(window.reservationDeadline()))
                .param("graceDeadline", ts(window.graceDeadline()));
    }

    /** STOMP 확정. 더 늦게 열린 세션이 이미 확정돼 있으면 옛 세션의 뒤늦은 확정은 0행이다 (단조 조건). */
    public int confirm(
            Long roomId, Long userId, String sessionId, Instant sessionOpenedAt, String taskId, Instant now) {
        return jdbc.sql("""
                        UPDATE room_participations
                           SET stomp_confirmed = true, stomp_session_id = :sessionId, session_opened_at = :openedAt,
                               task_id = :taskId, disconnected_at = NULL, joined_at = COALESCE(joined_at, :now)
                         WHERE room_id = :roomId AND user_id = :userId AND left_at IS NULL
                           AND (session_opened_at IS NULL OR session_opened_at <= :openedAt)""")
                .param("sessionId", sessionId)
                .param("openedAt", ts(sessionOpenedAt))
                .param("taskId", taskId)
                .param("now", ts(now))
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    /** 끊김 — 세션 ID로 바로 찾는다. 재접속이 먼저 도착했으면 세션이 이미 새 값이라 0행. */
    public int markDisconnected(String sessionId, Instant now) {
        return jdbc.sql(
                        "UPDATE room_participations SET disconnected_at = :now, stomp_session_id = NULL, task_id = NULL "
                                + "WHERE stomp_session_id = :sessionId AND left_at IS NULL")
                .param("now", ts(now))
                .param("sessionId", sessionId)
                .update();
    }

    /** 핫패스: 갱신 1행 = 인가(현재 세션의 확정 멤버) + 저장. null 필드는 COALESCE로 유지. */
    public int updateState(
            Long roomId, Long userId, String sessionId, Boolean cameraOn, String focusState, Integer focusSec) {
        return jdbc.sql("""
                        UPDATE room_participations
                           SET camera_on = COALESCE(:cameraOn, camera_on),
                               focus_state = COALESCE(:focusState, focus_state),
                               focus_sec = COALESCE(:focusSec, focus_sec)
                         WHERE room_id = :roomId AND user_id = :userId AND left_at IS NULL
                           AND stomp_confirmed AND stomp_session_id = :sessionId""")
                .param("cameraOn", cameraOn)
                .param("focusState", focusState)
                .param("focusSec", focusSec)
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .update();
    }

    public List<RoomMember> findConfirmedMembers(Long roomId) {
        return jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND left_at IS NULL AND stomp_confirmed ORDER BY id")
                .param("roomId", roomId)
                .query(MEMBER)
                .list();
    }

    /** 스냅샷 재요청 — 인가(요청자가 현재 세션의 확정 멤버)와 조회를 한 문장으로. */
    public List<RoomMember> findConfirmedMembersForActiveSession(Long roomId, Long userId, String sessionId) {
        return jdbc.sql("SELECT " + MEMBER_COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND left_at IS NULL AND stomp_confirmed "
                        + "AND EXISTS (SELECT 1 FROM room_participations me WHERE me.room_id = :roomId "
                        + "AND me.user_id = :userId AND me.left_at IS NULL AND me.stomp_confirmed "
                        + "AND me.stomp_session_id = :sessionId) ORDER BY id")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(MEMBER)
                .list();
    }

    public boolean existsLive(Long roomId, Long userId) {
        return exists("room_id = :roomId AND user_id = :userId AND left_at IS NULL", roomId, userId, null);
    }

    public boolean isConfirmed(Long roomId, Long userId) {
        return exists(
                "room_id = :roomId AND user_id = :userId AND left_at IS NULL AND stomp_confirmed",
                roomId,
                userId,
                null);
    }

    public boolean isActiveSession(Long roomId, Long userId, String sessionId) {
        return exists(
                "room_id = :roomId AND user_id = :userId AND left_at IS NULL AND stomp_confirmed "
                        + "AND stomp_session_id = :sessionId",
                roomId,
                userId,
                sessionId);
    }

    private boolean exists(String where, Long roomId, Long userId, String sessionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM room_participations WHERE " + where + ")")
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .single();
    }

    /** 시그널 인가용 — 발신자·수신자 라이브 행을 한 번에. */
    public List<Row> findLiveByRoomAndUsers(Long roomId, Collection<Long> userIds) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM room_participations "
                        + "WHERE room_id = :roomId AND user_id IN (:userIds) AND left_at IS NULL")
                .param("roomId", roomId)
                .param("userIds", userIds)
                .query(ROW)
                .list();
    }

    public List<Candidate> findExpiryCandidates(ExpiryWindow window) {
        return withWindow(
                        jdbc.sql("SELECT id, room_id, user_id FROM room_participations WHERE left_at IS NULL AND "
                                + EXPIRED + " ORDER BY room_id, id"),
                        window)
                .query(CANDIDATE)
                .list();
    }

    /** 죽은 태스크의 참가자를 끊김으로 전환한다 — 이후는 유예 만료 경로가 처리. */
    public int reclaimByTask(String taskId, Instant now) {
        return jdbc.sql("UPDATE room_participations SET disconnected_at = COALESCE(disconnected_at, :now), "
                        + "stomp_session_id = NULL, task_id = NULL WHERE task_id = :taskId AND left_at IS NULL")
                .param("now", ts(now))
                .param("taskId", taskId)
                .update();
    }

    /** 리스 행이 아예 없는 task_id의 참가자(등록 전에 죽은 태스크)도 같은 전환을 적용한다. */
    public int reclaimOrphansWithoutLease(Instant now) {
        return jdbc.sql("UPDATE room_participations SET disconnected_at = COALESCE(disconnected_at, :now), "
                        + "stomp_session_id = NULL, task_id = NULL WHERE left_at IS NULL AND task_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM live_task t WHERE t.task_id = room_participations.task_id)")
                .param("now", ts(now))
                .update();
    }
}
