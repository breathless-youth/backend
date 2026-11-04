package project.study.group.repository;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * user_report 테이블 (BY-1124) — 공부방 멤버 신고와 그룹 신청 신고. 쓰기만 하고, 운영자는 슬랙 알림과 DB/Metabase로 본다. V1의 옛
 * {@code report} 테이블은 쓰지 않는다(쓰는 코드가 없고 그룹·신청·종류 컬럼이 없다).
 */
@Repository
@RequiredArgsConstructor
public class ReportRepository {

    /** {@code type} 값 — 오타가 CHECK 제약 위반으로 늦게 드러나지 않게 이름을 붙여 둔다. */
    public static final String ROOM_MEMBER = "ROOM_MEMBER";

    public static final String APPLICATION = "APPLICATION";

    private final JdbcClient jdbc;

    /** 신고 한 건을 남긴다. 신청 ID는 신청 신고에만 있다. 사유는 정규화한 값이고 없으면 null이다. */
    public void insert(
            String type,
            long reporterId,
            long targetUserId,
            long groupId,
            Long applicationId,
            String reason,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO user_report
                            (reporter_id, target_user_id, type, group_id, application_id, reason, created_at)
                        VALUES (:reporterId, :targetUserId, :type, :groupId, :applicationId, :reason, :now)""")
                .param("reporterId", reporterId)
                .param("targetUserId", targetUserId)
                .param("type", type)
                .param("groupId", groupId)
                .param("applicationId", applicationId)
                .param("reason", reason)
                .param("now", Rows.ts(now))
                .update();
    }
}
