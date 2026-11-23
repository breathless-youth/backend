package project.study.group.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * group_study_alert 테이블과 멤버 공부 시작 알림이 보는 공부방 자리 (BY-1124). 받는 사람·그룹마다 마지막으로 보낸 때를 한 행으로 두고,
 * 조건부 upsert로 "1시간에 한 번"을 지킨다 — 서버가 여러 대여도 한 쌍은 한 번만 보낸다(ShedLock 없음).
 */
@Repository
@RequiredArgsConstructor
public class GroupAlertRepository {

    private final JdbcClient jdbc;

    /**
     * 받는 사람들 중 이번에 보내도 되는 사람을 정하고 그 사람들의 마지막 보낸 때를 {@code now}로 바꾼다. 기록이 없거나 마지막 보낸 때가
     * 1시간 전({@code now - 1시간}) 이전이거나 같을 때만 넣거나 갱신하고, 돌려받은 사람에게만 보낸다. 같은 쌍을 동시에 다루는 두 문장은 행 락에서
     * 만나 늦은 쪽이 갱신된 시각을 다시 보고 빠진다.
     *
     * <p>{@code users}를 거치는 것은 중복 ID가 한 문장에 두 번 들어가 {@code ON CONFLICT}가 실패하는 일을 막고, id 순으로 넣어 같은 쌍을 다루는
     * 두 문장이 서로 반대 순서로 행을 잡아 교착하지 않게 하려는 것이다.
     */
    public List<Long> claim(Collection<Long> receiverIds, long groupId, Instant now) {
        if (receiverIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        INSERT INTO group_study_alert (receiver_id, group_id, last_sent_at)
                        SELECT u.id, :groupId, :now FROM users u WHERE u.id IN (:receiverIds) ORDER BY u.id
                        ON CONFLICT (receiver_id, group_id) DO UPDATE SET last_sent_at = excluded.last_sent_at
                         WHERE group_study_alert.last_sent_at <= excluded.last_sent_at - interval '1 hour'
                        RETURNING receiver_id""")
                .param("groupId", groupId)
                .param("now", Rows.ts(now))
                .param("receiverIds", receiverIds)
                .query(Long.class)
                .list();
    }

    public record StudyStart(long participationId, long roomId, long starterId) {}

    public record AlertGroup(long id, String name, boolean studying) {}

    /**
     * 아직 알리지 않은 그룹 공부방 자리 중 현재 연결되어 실제 공부 중인 자리만 가져온다.
     * 카메라가 꺼진 입장 상태에서는 대기하고, 같은 자리에 복귀하거나 카메라를 다시 켜도 한 번만 꺼낸다.
     */
    public List<StudyStart> claimStudyStarts() {
        return jdbc.sql("""
                        WITH ready AS (
                            SELECT p.id
                              FROM room_participations p
                              JOIN rooms r ON r.id = p.room_id
                             WHERE NOT p.group_start_notified AND p.left_at IS NULL
                               AND p.stomp_confirmed AND p.stomp_session_id IS NOT NULL
                               AND p.camera_on AND NOT p.on_break
                               AND r.group_id IS NOT NULL AND r.closed_at IS NULL
                             ORDER BY p.id
                               FOR UPDATE OF p SKIP LOCKED
                        )
                        UPDATE room_participations p SET group_start_notified = true
                          FROM ready
                         WHERE ready.id = p.id
                        RETURNING p.id, p.room_id, p.user_id""")
                .query((rs, n) -> new StudyStart(rs.getLong("id"), rs.getLong("room_id"), rs.getLong("user_id")))
                .list();
    }

    /** 시작을 꺼낸 뒤 방을 옮기거나 멤버십이 끝났으면 보낼 대상 그룹이 없다. */
    public Optional<AlertGroup> findStudyGroup(StudyStart start) {
        return jdbc.sql("""
                        SELECT g.id, g.name,
                               (p.stomp_confirmed AND p.stomp_session_id IS NOT NULL
                                AND p.camera_on AND NOT p.on_break) AS studying
                          FROM rooms r
                          JOIN study_group g ON g.id = r.group_id AND g.deleted_at IS NULL
                          JOIN study_group_member m ON m.group_id = g.id AND m.user_id = :starterId
                           AND m.left_at IS NULL
                          JOIN room_participations p ON p.id = :participationId AND p.room_id = r.id AND p.user_id = m.user_id
                           AND p.left_at IS NULL
                         WHERE r.id = :roomId AND r.closed_at IS NULL""")
                .param("participationId", start.participationId())
                .param("roomId", start.roomId())
                .param("starterId", start.starterId())
                .query((rs, n) -> new AlertGroup(rs.getLong("id"), rs.getString("name"), rs.getBoolean("studying")))
                .optional();
    }

    /** 잠깐 멈췄거나 연결이 끊긴 자리의 시작을 다음 공부 시점에 다시 확인한다. */
    public void releaseStudyStart(long participationId) {
        jdbc.sql("UPDATE room_participations SET group_start_notified = false WHERE id = :id AND left_at IS NULL")
                .param("id", participationId)
                .update();
    }

    /** 예약과 끊김 유예 중인 자리도 포함한다. */
    public List<Long> findSeatHolders(long roomId) {
        return jdbc.sql("SELECT user_id FROM room_participations WHERE room_id = :roomId AND left_at IS NULL")
                .param("roomId", roomId)
                .query(Long.class)
                .list();
    }
}
