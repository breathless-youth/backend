package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.group.repository.GroupRepository;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.push.service.PushSender;
import project.study.user.account.AccountData;

/**
 * 계정 병합·탈퇴가 users 행보다 먼저 잡는 그룹 행 (BY-1126) — 어느 그룹을 어떤 모드로 잡는지 고정한다. 잠근 트랜잭션을 연 채 다른 연결에서
 * {@code NOWAIT}로 잡아 본다: 그룹 쪽 공유 락({@code FOR SHARE}, {@code shareLive})은 막혀야 하고, 그룹을 가리키는 행을 넣는 FK 검사({@code FOR
 * KEY SHARE})는 막히면 안 된다 — 막히면 users 행을 쥔 채 그 검사를 기다리는 신고·알림 기록과 교착한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupAccountLockTest {

    /** Postgres의 lock_not_available — NOWAIT로 잡지 못했다. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private AccountData accountData;

    @Autowired
    private GroupRepository groups;

    @MockitoBean
    private PushSender pushSender;

    private long user;
    private long owned;
    private long joined;
    private long applied;
    private long unrelated;
    private long deletedOwned;

    @BeforeEach
    void setUp() {
        GroupProbe probe = new GroupProbe(jdbc);
        user = probe.insertUser();
        long other = probe.insertUser();
        // 그룹장 조건만 걸리게 그룹장 자신의 소속 행은 닫는다
        owned = probe.insertGroup(user, "PUBLIC");
        probe.leaveGroup(owned, user);
        joined = probe.joinNewGroupOf(user);
        applied = probe.insertGroup(other, "PUBLIC");
        probe.insertApplication(applied, user, "PENDING", NOW.plusSeconds(3600));
        // 나간 소속·답한 신청만 있는 그룹은 얽힌 그룹이 아니다
        unrelated = probe.insertGroup(other, "PUBLIC");
        probe.insertLeftMember(unrelated, user, "LEFT", NOW.minusSeconds(60));
        probe.insertApplication(unrelated, user, "DECLINED", NOW.plusSeconds(3600));
        deletedOwned = probe.insertGroup(user, "PRIVATE");
        probe.softDeleteGroup(deletedOwned);
    }

    /** 다른 연결에서 이 그룹 행을 {@code lockClause NOWAIT}로 잡아 본다. 잡지 못하면 true. */
    private boolean blocked(long groupId, String lockClause) {
        try (Connection other = dataSource.getConnection();
                PreparedStatement statement =
                        other.prepareStatement("SELECT 1 FROM study_group WHERE id = ? " + lockClause + " NOWAIT")) {
            other.setAutoCommit(true);
            statement.setLong(1, groupId);
            statement.executeQuery().close();
            return false;
        } catch (SQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                return true;
            }
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 사용자_행보다_먼저_얽힌_살아_있는_그룹만_잡고_FK_검사는_막지_않는다() {
        tx.executeWithoutResult(status -> {
            accountData.lockBeforeUsers(user);

            assertThat(blocked(owned, "FOR SHARE")).as("그룹장인 그룹").isTrue();
            assertThat(blocked(joined, "FOR SHARE")).as("살아 있는 멤버인 그룹").isTrue();
            assertThat(blocked(applied, "FOR SHARE")).as("답을 기다리는 신청을 낸 그룹").isTrue();
            assertThat(blocked(unrelated, "FOR SHARE")).as("나간 소속·답한 신청만 있는 그룹").isFalse();
            assertThat(blocked(deletedOwned, "FOR SHARE")).as("지운 그룹").isFalse();
            assertThat(blocked(owned, "FOR KEY SHARE")).as("신고·알림 기록의 FK 검사").isFalse();
            status.setRollbackOnly();
        });
    }

    @Test
    void 탈퇴가_그룹마다_다시_잡을_때도_FK_검사는_막지_않는다() {
        tx.executeWithoutResult(status -> {
            groups.lockLiveOfMemberOrOwner(user);

            assertThat(blocked(owned, "FOR SHARE")).isTrue();
            assertThat(blocked(joined, "FOR SHARE")).isTrue();
            assertThat(blocked(applied, "FOR SHARE"))
                    .as("신청만 한 그룹은 그룹장을 정할 일이 없다")
                    .isFalse();
            assertThat(blocked(joined, "FOR KEY SHARE")).isFalse();
            status.setRollbackOnly();
        });
    }
}
