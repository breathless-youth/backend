package project.study.group.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 그룹마다 그 그룹의 열린 공부방에 자리가 있는 사람 — 예약만 한 자리와 끊김 유예 중인 자리도 자리다({@code left_at IS NULL}). 이미 방에
     * 있거나 곧 들어올 사람에게는 "같이 공부하러 가 볼까요?"를 보내지 않는다. 다른 그룹 방이나 초대코드 방에 앉은 사람은 이 그룹 방에는 자리가
     * 없으므로 들지 않는다. 자리가 없는 그룹은 맵에 키가 없다.
     */
    public Map<Long, Set<Long>> findSeatHolders(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> seats = new HashMap<>();
        jdbc.sql("""
                        SELECT r.group_id, p.user_id
                          FROM rooms r
                          JOIN room_participations p ON p.room_id = r.id AND p.left_at IS NULL
                         WHERE r.group_id IN (:groupIds) AND r.closed_at IS NULL""").param("groupIds", groupIds).query(rs -> {
            seats.computeIfAbsent(rs.getLong("group_id"), id -> new HashSet<>()).add(rs.getLong("user_id"));
        });
        return seats;
    }
}
