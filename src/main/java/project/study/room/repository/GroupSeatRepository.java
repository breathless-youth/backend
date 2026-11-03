package project.study.room.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.room.dto.StateUpdatePayload;

/**
 * room_participations에서 그룹 공부방 표시값(mic_on·on_break)을 쓰고 읽는 문장 (BY-1124). 초대코드 방의 지금 문장은
 * {@link RoomParticipationRepository}에 그대로 두고, 그룹 공부방 값이 필요한 경로만 여기를 탄다 — 초대코드 방·옛 앱의 핫패스가 바뀌지 않게.
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

    /** 유예 복귀 응답이 이어 줄 값 — 호출자가 이 자리 행을 잠근 채 부른다. */
    public GroupFlags flags(Long participationId) {
        return jdbc.sql("SELECT mic_on, on_break FROM room_participations WHERE id = :id")
                .param("id", participationId)
                .query((rs, n) -> new GroupFlags(rs.getBoolean("mic_on"), rs.getBoolean("on_break")))
                .single();
    }
}
