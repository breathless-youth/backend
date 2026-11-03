package project.study.group.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 멤버 책상(BY-1124)이 그룹 테이블 밖에서 읽는 것 — 사람의 닉네임·사진과 그룹 공부방 자리. 둘 다 사람·그룹 집합 단위로 한 번씩 읽는다(사람마다
 * 쿼리하지 않는다).
 */
@Repository
@RequiredArgsConstructor
public class GroupDeskQueries {

    /** 책상에 그릴 사람 정보. 사진은 저장된 키라 응답 경로로 바꿔 써야 한다. */
    public record MemberProfile(String nickname, String profileImage) {}

    private final JdbcClient jdbc;

    public Map<Long, MemberProfile> profiles(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MemberProfile> profiles = new HashMap<>();
        jdbc.sql("SELECT id, nickname, profile_image FROM users WHERE id IN (:userIds)")
                .param("userIds", userIds)
                .query(rs -> {
                    profiles.put(
                            rs.getLong("id"),
                            new MemberProfile(rs.getString("nickname"), rs.getString("profile_image")));
                });
        return profiles;
    }

    /**
     * 그룹마다 그 그룹의 열린 공부방에 확정된 자리가 있는 사람 — 책상의 {@code IN_ROOM}은 이것으로만 정한다(다른 데서 그룹 공부방 여부를 추측하지
     * 않는다). 열린 방은 {@code rooms.group_id = 그룹 AND closed_at IS NULL}, 확정된 자리는 {@code left_at IS NULL AND stomp_confirmed}다.
     *
     * <p>{@code rooms.group_id}는 그룹 공부방 단계(Task 6)가 만든다 — 그 전에는 그룹 공부방이 없으므로 빈 맵이고, 그 단계에서 한 쿼리로 채운다.
     */
    public Map<Long, Set<Long>> confirmedRoomSeats(Collection<Long> groupIds) {
        return Map.of();
    }
}
