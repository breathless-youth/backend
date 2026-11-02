package project.study.rtcstats;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.user.account.AccountData;
import project.study.user.account.UserDataFixture;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RtcStatsUserDataHandlerTest {

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Test
    void 병합은_통계_행을_그대로_둔다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedRtcStat(1L, source, null);

        accountData.merge(source, target);

        assertThat(fixture.count("rtc_connection_stat", "user_id", source)).isEqualTo(1);
        assertThat(fixture.count("rtc_connection_stat", "user_id", target)).isZero();
    }

    @Test
    void 삭제하면_본인_행은_지우고_상대로_적힌_자리는_비운다() {
        long user = fixture.seedUser();
        long other = fixture.seedUser();
        fixture.seedRtcStat(1L, user, other);
        fixture.seedRtcStat(1L, other, user);

        accountData.erase(user);

        assertThat(fixture.count("rtc_connection_stat", "user_id", user)).isZero();
        assertThat(fixture.count("rtc_connection_stat", "peer_user_id", user)).isZero();
        assertThat(fixture.count("rtc_connection_stat", "user_id", other)).isEqualTo(1);
    }
}
