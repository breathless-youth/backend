package project.study.room.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.room.dto.StateUpdatePayload;

/**
 * room_participations에서 그룹 공부방 표시값(mic_on·on_break)을 쓰고 읽는 문장과, 그룹 공부방에서만 보는 차단한 사이(user_block) 판정
 * (BY-1124). 초대코드 방의 지금 문장은 {@link RoomParticipationRepository}에 그대로 두고, 그룹 공부방 값이 필요한 경로만 여기를 탄다 — 초대코드
 * 방·옛 앱의 핫패스가 바뀌지 않게. 방 종류는 같은 문장 안에서 {@code rooms.group_id}로 가려, 판정하려고 한 번 더 조회하지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class GroupSeatRepository {

    /** 그룹 공부방 자리의 마이크·휴식 표시값. */
    public record GroupFlags(boolean micOn, boolean onBreak) {}

    private final JdbcClient jdbc;

    /**
     * 그룹 공부방 필드(micOn·onBreak)가 있는 state 보고. 갱신 1행 = 인가(현재 세션의 확정 멤버) + 저장은
     * {@link RoomParticipationRepository#updateState}와 같고, 두 필드는 방이 그룹 공부방일 때만 반영한다 — 초대코드 방이면 그대로 두고 나머지
     * 필드만 반영한다. 갱신했으면 그룹 공부방인지를, 0행이면 빈 값을 돌려준다(방 종류를 알려고 한 번 더 조회하지 않게).
     *
     * <p>rooms는 읽기만 하고 잠그지 않는다 — group_id는 방이 열린 뒤 바뀌지 않는다.
     */
    public Optional<Boolean> updateState(Long roomId, Long userId, String sessionId, StateUpdatePayload change) {
        return jdbc.sql("""
                        UPDATE room_participations p
                           SET camera_on = COALESCE(:cameraOn, p.camera_on),
                               focus_state = COALESCE(:focusState, p.focus_state),
                               focus_sec = COALESCE(:focusSec, p.focus_sec),
                               mic_on = CASE WHEN r.group_id IS NULL THEN p.mic_on ELSE COALESCE(:micOn, p.mic_on) END,
                               on_break = CASE WHEN r.group_id IS NULL THEN p.on_break
                                               ELSE COALESCE(:onBreak, p.on_break) END
                          FROM rooms r
                         WHERE r.id = p.room_id AND p.room_id = :roomId AND p.user_id = :userId AND p.left_at IS NULL
                           AND p.stomp_confirmed AND p.stomp_session_id = :sessionId
                        RETURNING (r.group_id IS NOT NULL) AS group_room""")
                .param("cameraOn", change.cameraOn())
                .param("focusState", change.focusState())
                .param("focusSec", change.focusSec())
                .param("micOn", change.micOn())
                .param("onBreak", change.onBreak())
                .param("roomId", roomId)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .optional();
    }

    /**
     * SIGNAL 인가 — 발신자가 현재 세션의 확정 멤버이고 수신자가 확정 멤버이며, 그룹 공부방이면 두 사람이 어느 쪽으로도 차단한 사이가 아닐 때만 참이다.
     * 한 문장이라 SIGNAL마다 쿼리는 지금처럼 하나다. 초대코드 방은 {@code group_id}가 없어 차단을 보지 않는다(지금 그대로). 자기 자신에게 보내는
     * SIGNAL(부하 테스트 경로)은 차단이 있을 수 없어 지금처럼 통과한다.
     */
    public boolean signalAllowed(Long roomId, Long fromUserId, String sessionId, Long toUserId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM room_participations
                                        WHERE room_id = :roomId AND user_id = :fromUserId AND left_at IS NULL
                                          AND stomp_confirmed AND stomp_session_id = :sessionId)
                           AND EXISTS (SELECT 1 FROM room_participations
                                        WHERE room_id = :roomId AND user_id = :toUserId AND left_at IS NULL
                                          AND stomp_confirmed)
                           AND NOT EXISTS (SELECT 1 FROM rooms r
                                             JOIN user_block b
                                               ON (b.blocker_id = :fromUserId AND b.blocked_id = :toUserId)
                                               OR (b.blocker_id = :toUserId AND b.blocked_id = :fromUserId)
                                            WHERE r.id = :roomId AND r.group_id IS NOT NULL)""")
                .param("roomId", roomId)
                .param("fromUserId", fromUserId)
                .param("sessionId", sessionId)
                .param("toUserId", toUserId)
                .query(Boolean.class)
                .single();
    }

    /** 두 사람이 같은 그룹 공부방에 자리가 있는가 — 예약만 한 자리와 끊김 유예 중인 자리도 자리다. 초대코드 방에 함께 있는 것은 세지 않는다. */
    public boolean shareGroupRoom(long userId, long otherUserId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM room_participations a
                                         JOIN room_participations b
                                           ON b.room_id = a.room_id AND b.user_id = :otherUserId AND b.left_at IS NULL
                                         JOIN rooms r ON r.id = a.room_id AND r.group_id IS NOT NULL
                                        WHERE a.user_id = :userId AND a.left_at IS NULL)""")
                .param("userId", userId)
                .param("otherUserId", otherUserId)
                .query(Boolean.class)
                .single();
    }

    /** 유예 복귀 응답이 이어 줄 값 — 호출자가 이 자리 행을 잠근 채 부른다. */
    public GroupFlags flags(Long participationId) {
        return jdbc.sql("SELECT mic_on, on_break FROM room_participations WHERE id = :id")
                .param("id", participationId)
                .query((rs, n) -> new GroupFlags(rs.getBoolean("mic_on"), rs.getBoolean("on_break")))
                .single();
    }
}
