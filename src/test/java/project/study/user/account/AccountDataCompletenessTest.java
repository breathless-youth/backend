package project.study.user.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;

/** 모든 처리기 테이블에 행이 있는 사용자의 병합·삭제가 FK 위반 없이 끝나고 users 행을 지울 수 있어야 한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountDataCompletenessTest {

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    private long countUser(long id) {
        return jdbc.sql("SELECT count(*) FROM users WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    @Test
    void 병합하면_옮기는_쪽을_가리키는_행이_없고_users_행을_지울_수_있다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedEverything(source, target);
        fixture.seedEverything(target, source);

        assertThat(fixture.countRowsReferencing(source)).isPositive();

        accountData.merge(source, target);

        // 명세상 예외: RTC 통계는 병합에서 그대로 둔다 (users FK 없음)
        Set<String> rtcColumns = Set.of("rtc_connection_stat.user_id", "rtc_connection_stat.peer_user_id");
        assertThat(fixture.count("rtc_connection_stat", "user_id", source)).isPositive();
        assertThat(fixture.countRowsReferencing(source, rtcColumns)).isZero();
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", source).update();
        assertThat(countUser(source)).isZero();
    }

    @Test
    void 삭제하면_지운_사용자를_가리키는_행이_없고_users_행을_지울_수_있다() {
        long user = fixture.seedUser();
        long other = fixture.seedUser();
        fixture.seedEverything(user, other);
        fixture.seedEverything(other, user);

        assertThat(fixture.countRowsReferencing(user)).isPositive();

        accountData.erase(user);

        assertThat(fixture.countRowsReferencing(user)).isZero();
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", user).update();
        assertThat(countUser(user)).isZero();
    }
}
