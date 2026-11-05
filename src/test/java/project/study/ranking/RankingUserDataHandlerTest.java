package project.study.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.user.account.AccountData;
import project.study.user.account.UserDataFixture;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RankingUserDataHandlerTest {

    private static final LocalDate D1 = LocalDate.of(2030, 3, 1);
    private static final LocalDate D2 = LocalDate.of(2030, 3, 2);

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    private int bestRank(long userId) {
        return jdbc.sql("SELECT rank FROM ranking_best WHERE user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    @Test
    void 같은_순위판_기간의_기록은_남는_쪽_것만_남는다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        String board = "board-" + source;
        fixture.seedRankingRecord(target, board, D1, 1);
        fixture.seedRankingRecord(source, board, D1, 2);
        fixture.seedRankingRecord(source, board, D2, 1);

        accountData.merge(source, target);

        assertThat(fixture.count("ranking_record", "user_id", target)).isEqualTo(2);
        assertThat(fixture.count("ranking_record", "user_id", source)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ranking_record WHERE board_key = :b AND period_start = :p")
                        .param("b", board)
                        .param("p", D1)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void 더_좋은_최고_순위는_남는_쪽으로_옮겨진다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedRankingBest(source, 1, "b", D1);
        fixture.seedRankingBest(target, 3, "b", D2);

        accountData.merge(source, target);

        assertThat(bestRank(target)).isEqualTo(1);
        assertThat(fixture.count("ranking_best", "user_id", source)).isZero();
    }

    @Test
    void 더_나쁘거나_같은_최고_순위는_남는_쪽이_이긴다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedRankingBest(source, 3, "b", D1);
        fixture.seedRankingBest(target, 3, "b", D2);

        accountData.merge(source, target);

        assertThat(bestRank(target)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT period_start FROM ranking_best WHERE user_id = :u")
                        .param("u", target)
                        .query(LocalDate.class)
                        .single())
                .isEqualTo(D2);
        assertThat(fixture.count("ranking_best", "user_id", source)).isZero();
    }

    @Test
    void 같은_판_기간에서_옮기는_쪽이_더_높았어도_그_기록이_지워졌으면_남는_쪽_최고가_그대로다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        String board = "board-" + source;
        // 둘 다 같은 마감에 들었다 — 부딪히면 남는 쪽이 이기므로 옮기는 쪽 1위 기록은 지워진다
        fixture.seedRankingRecord(source, board, D1, 1);
        fixture.seedRankingRecord(target, board, D1, 2);
        fixture.seedRankingBest(source, 1, board, D1);
        fixture.seedRankingBest(target, 2, board, D1);

        accountData.merge(source, target);

        assertThat(bestRank(target)).isEqualTo(2);
        assertThat(fixture.count("ranking_best", "user_id", source)).isZero();
    }

    @Test
    void 기록이_옮겨진_최고_순위는_남는_쪽으로_넘어간다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        String board = "board-" + source;
        fixture.seedRankingRecord(source, board, D1, 1);
        fixture.seedRankingRecord(target, board, D2, 2);
        fixture.seedRankingBest(source, 1, board, D1);
        fixture.seedRankingBest(target, 2, board, D2);

        accountData.merge(source, target);

        assertThat(bestRank(target)).isEqualTo(1);
    }

    @Test
    void 메달_기록이_없는_순위도_같은_마감이면_남는_쪽이_이긴다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        // 4위 밖은 ranking_record가 없고 ranking_best만 남는다 — 같은 판·기간이면 같은 마감에 둘 다 든 것이다
        fixture.seedRankingBest(source, 4, "b", D1);
        fixture.seedRankingBest(target, 6, "b", D1);

        accountData.merge(source, target);

        assertThat(bestRank(target)).isEqualTo(6);
    }

    @Test
    void 남는_쪽에_최고_순위가_없으면_옮기는_쪽_것이_넘어간다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedRankingBest(source, 2, "b", D1);

        accountData.merge(source, target);

        assertThat(bestRank(target)).isEqualTo(2);
        assertThat(fixture.count("ranking_best", "user_id", source)).isZero();
    }

    @Test
    void 삭제하면_기록과_최고_순위가_모두_지워진다() {
        long user = fixture.seedUser();
        fixture.seedRankingRecord(user, "board-" + user, D1, 1);
        fixture.seedRankingBest(user, 1, "b", D1);

        accountData.erase(user);

        assertThat(fixture.count("ranking_record", "user_id", user)).isZero();
        assertThat(fixture.count("ranking_best", "user_id", user)).isZero();
    }
}
