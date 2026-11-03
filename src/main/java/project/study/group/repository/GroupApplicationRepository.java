package project.study.group.repository;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * study_group_application 테이블 (BY-1124). 지금은 한도 계산·그룹 홈에 필요한 개수 조회만 있고, 신청 만들기·답하기는 신청 단계에서 넓힌다.
 *
 * <p>"답을 기다리는 신청"은 {@code status = 'PENDING' AND expires_at > now}다 — 만료 작업이 아직 안 돌았어도 기한이 지난 신청은 세지
 * 않는다.
 */
@Repository
@RequiredArgsConstructor
public class GroupApplicationRepository {

    private final JdbcClient jdbc;

    /** 이 사용자가 답을 기다리는 신청 수 — "그룹+신청 5개"와 "신청 3개" 한도가 쓴다. */
    public int countPendingOfUser(long userId, Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FROM study_group_application
                         WHERE user_id = :userId AND status = 'PENDING' AND expires_at > :now""")
                .param("userId", userId)
                .param("now", Rows.ts(now))
                .query(Integer.class)
                .single();
    }

    /** 이 그룹에 답을 기다리는 신청 수 — 그룹 홈의 새 신청 띠가 쓴다. */
    public int countPendingOfGroup(long groupId, Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FROM study_group_application
                         WHERE group_id = :groupId AND status = 'PENDING' AND expires_at > :now""")
                .param("groupId", groupId)
                .param("now", Rows.ts(now))
                .query(Integer.class)
                .single();
    }
}
