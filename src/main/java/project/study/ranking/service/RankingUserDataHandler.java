package project.study.ranking.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 계정 병합·탈퇴 시 랭킹 처리. 같은 판·기간의 기록은 남는 쪽 것만 남기고, 개인 최고는 더 좋은 순위(작은 값)가 이긴다. */
@Component
@RequiredArgsConstructor
public class RankingUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("ranking_record.user_id", "ranking_best.user_id");
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        jdbc.sql("""
                        DELETE FROM ranking_record s
                        WHERE s.user_id = :source
                          AND EXISTS (SELECT 1 FROM ranking_record t
                                      WHERE t.user_id = :target
                                        AND t.board_key = s.board_key AND t.period_start = s.period_start)""")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("UPDATE ranking_record SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();

        // RankingCloseRepository.UPSERT_BEST와 같은 조건: 더 좋은 순위일 때만 덮어쓴다
        jdbc.sql("""
                        INSERT INTO ranking_best (user_id, rank, board_key, period_start)
                        SELECT :target, rank, board_key, period_start FROM ranking_best WHERE user_id = :source
                        ON CONFLICT (user_id) DO UPDATE
                        SET rank = excluded.rank,
                            board_key = excluded.board_key,
                            period_start = excluded.period_start
                        WHERE excluded.rank < ranking_best.rank""")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("DELETE FROM ranking_best WHERE user_id = :source")
                .param("source", sourceUserId)
                .update();
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM ranking_record WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM ranking_best WHERE user_id = :id")
                .param("id", userId)
                .update();
    }
}
