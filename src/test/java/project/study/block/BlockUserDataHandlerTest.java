package project.study.block;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.group.support.BlockProbe;
import project.study.user.account.AccountData;
import project.study.user.account.UserDataFixture;

/** 계정 병합·탈퇴 때 차단 (BY-1126) — 병합은 양쪽 방향을 남는 쪽으로 합치고 자기 자신 차단·중복을 버린다. 탈퇴는 양쪽 방향을 모두 지운다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class BlockUserDataHandlerTest {

    private static final Instant EARLIER = Instant.parse("2030-03-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2030-03-02T10:00:00Z");

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    private BlockProbe blocks;

    @BeforeEach
    void setUp() {
        blocks = new BlockProbe(jdbc);
    }

    private long blockRowsOf(long userId) {
        return fixture.count("user_block", "blocker_id", userId) + fixture.count("user_block", "blocked_id", userId);
    }

    @Test
    void 병합하면_양쪽_방향_차단을_남는_쪽으로_합치고_자기_자신_차단과_중복은_버린다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        long both = fixture.seedUser();
        long onlySource = fixture.seedUser();
        long blocksSource = fixture.seedUser();
        // 서로 차단 — 합치면 자기 자신 차단이 된다
        blocks.insertBlock(source, target, LATER);
        blocks.insertBlock(target, source, LATER);
        // 둘 다 같은 사람을 차단했거나 같은 사람에게 차단됐다 — 남는 쪽 행(먼저 차단한 시각)이 이긴다
        blocks.insertBlock(target, both, EARLIER);
        blocks.insertBlock(source, both, LATER);
        blocks.insertBlock(both, target, EARLIER);
        blocks.insertBlock(both, source, LATER);
        // 옮기는 쪽만 가진 차단은 양쪽 방향 모두 옮긴다
        blocks.insertBlock(source, onlySource, LATER);
        blocks.insertBlock(blocksSource, source, LATER);

        accountData.merge(source, target);

        assertThat(blockRowsOf(source)).isZero();
        assertThat(blocks.blockedAt(target, target)).isEmpty();
        assertThat(blocks.blockedAt(target, both)).contains(EARLIER);
        assertThat(blocks.blockedAt(both, target)).contains(EARLIER);
        assertThat(blocks.blockedAt(target, onlySource)).contains(LATER);
        assertThat(blocks.blockedAt(blocksSource, target)).contains(LATER);
        assertThat(fixture.count("user_block", "blocker_id", target)).isEqualTo(2);
        assertThat(fixture.count("user_block", "blocked_id", target)).isEqualTo(2);
    }

    @Test
    void 탈퇴하면_건_차단과_받은_차단을_모두_지우고_다른_사람끼리의_차단은_남긴다() {
        long user = fixture.seedUser();
        long a = fixture.seedUser();
        long b = fixture.seedUser();
        blocks.insertBlock(user, a, EARLIER);
        blocks.insertBlock(b, user, EARLIER);
        blocks.insertBlock(a, b, EARLIER);

        accountData.erase(user);

        assertThat(blockRowsOf(user)).isZero();
        assertThat(blocks.blockedAt(a, b)).contains(EARLIER);
    }
}
