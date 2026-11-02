package project.study.user.account;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** user 패키지가 소유한 테이블(리프레시 토큰·신고·문의·알림·기간별 통계)의 병합·삭제. */
@Component
@RequiredArgsConstructor
public class UserAccountDataHandler implements UserDataHandler {

    /** PK가 (user_id, 날짜)인 통계 테이블과 그 날짜 컬럼. */
    private static final List<String[]> STATS = List.of(
            new String[] {"study_stats_daily", "stat_date"},
            new String[] {"study_stats_weekly", "period_start"},
            new String[] {"study_stats_monthly", "period_start"});

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of(
                "refresh_token.user_id",
                "report.report_user_id",
                "report.target_user_id",
                "inquiry.user_id",
                "user_notification.user_id",
                "study_stats_daily.user_id",
                "study_stats_weekly.user_id",
                "study_stats_monthly.user_id");
    }

    @Override
    public int order() {
        return 900;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        // 로그인 세션은 옮기지 않는다 — 옮기는 쪽 토큰은 전량 폐기
        delete("DELETE FROM refresh_token WHERE user_id = :id", sourceUserId);
        move("UPDATE report SET report_user_id = :target WHERE report_user_id = :source", sourceUserId, targetUserId);
        move("UPDATE report SET target_user_id = :target WHERE target_user_id = :source", sourceUserId, targetUserId);
        move("UPDATE inquiry SET user_id = :target WHERE user_id = :source", sourceUserId, targetUserId);
        move("UPDATE user_notification SET user_id = :target WHERE user_id = :source", sourceUserId, targetUserId);
        for (String[] stats : STATS) {
            String table = stats[0];
            String key = stats[1];
            jdbc.sql("DELETE FROM " + table + " s WHERE s.user_id = :source AND EXISTS ("
                            + "SELECT 1 FROM " + table + " t WHERE t.user_id = :target AND t." + key + " = s." + key
                            + ")")
                    .param("source", sourceUserId)
                    .param("target", targetUserId)
                    .update();
            move("UPDATE " + table + " SET user_id = :target WHERE user_id = :source", sourceUserId, targetUserId);
        }
    }

    @Override
    public void erase(long userId) {
        delete("DELETE FROM refresh_token WHERE user_id = :id", userId);
        jdbc.sql("DELETE FROM report WHERE report_user_id = :id OR target_user_id = :id")
                .param("id", userId)
                .update();
        delete("DELETE FROM inquiry WHERE user_id = :id", userId);
        delete("DELETE FROM user_notification WHERE user_id = :id", userId);
        for (String[] stats : STATS) {
            delete("DELETE FROM " + stats[0] + " WHERE user_id = :id", userId);
        }
    }

    private void delete(String sql, long id) {
        jdbc.sql(sql).param("id", id).update();
    }

    private void move(String sql, long source, long target) {
        jdbc.sql(sql).param("source", source).param("target", target).update();
    }
}
