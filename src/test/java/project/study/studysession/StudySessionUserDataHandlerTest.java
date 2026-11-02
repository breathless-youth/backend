package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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
class StudySessionUserDataHandlerTest {

    private static final Instant BASE = Instant.parse("2030-03-01T10:00:00Z");

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    private static Instant at(int hour, int minute) {
        return BASE.plusSeconds((hour - 10) * 3600L + minute * 60L);
    }

    private List<Instant> startsOf(long userId) {
        return jdbc.sql("SELECT started_at FROM study_session WHERE user_id = :u ORDER BY started_at")
                .param("u", userId)
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .list();
    }

    @Test
    void 겹치는_세션은_지우고_겹치지_않는_세션은_옮긴다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), at(11, 0));
        fixture.seedSession(source, at(10, 30), at(11, 30));
        fixture.seedSession(source, at(12, 0), at(13, 0));

        accountData.merge(source, target);

        assertThat(startsOf(source)).isEmpty();
        assertThat(startsOf(target)).containsExactly(at(10, 0), at(12, 0));
    }

    @Test
    void 끝과_시작이_맞닿은_세션은_옮긴다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), at(11, 0));
        fixture.seedSession(source, at(11, 0), at(12, 0));

        accountData.merge(source, target);

        assertThat(startsOf(target)).containsExactly(at(10, 0), at(11, 0));
    }

    @Test
    void ended_at이_없는_세션도_처리한다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), at(11, 0));
        fixture.seedSession(source, at(12, 0), null);

        accountData.merge(source, target);

        assertThat(startsOf(source)).isEmpty();
        assertThat(startsOf(target)).containsExactly(at(10, 0), at(12, 0));
    }

    @Test
    void 겹쳐서_지운_세션의_status_event도_사라진다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), at(11, 0));
        long overlapped = fixture.seedSession(source, at(10, 30), at(11, 30));
        fixture.seedStatusEvent(overlapped);

        accountData.merge(source, target);

        assertThat(fixture.count("status_event", "session_id", overlapped)).isZero();
        assertThat(fixture.count("study_session", "id", overlapped)).isZero();
    }

    @Test
    void 같은_started_at의_draft는_지우고_나머지는_옮긴다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedActiveSession(target, at(10, 0));
        fixture.seedActiveSession(source, at(10, 0));
        fixture.seedActiveSession(source, at(14, 0));
        // started_at이 같은 완료 세션(길이 0이라 범위 겹침으로는 안 잡힌다)도 unique 키 충돌이라 지운다
        fixture.seedSession(target, at(9, 0), at(9, 0));
        fixture.seedSession(source, at(9, 0), at(9, 0));

        accountData.merge(source, target);

        assertThat(fixture.count("active_study_session", "user_id", source)).isZero();
        assertThat(fixture.count("active_study_session", "user_id", target)).isEqualTo(2);
        assertThat(startsOf(source)).isEmpty();
        assertThat(startsOf(target)).containsExactly(at(9, 0));
    }

    @Test
    void 끝이_없는_세션이_남는_쪽_세션_안에서_시작하면_지운다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), at(11, 0));
        fixture.seedSession(source, at(10, 30), null, 0);

        accountData.merge(source, target);

        assertThat(startsOf(source)).isEmpty();
        assertThat(startsOf(target)).containsExactly(at(10, 0));
    }

    @Test
    void 남는_쪽_세션에_끝이_없어도_겹침을_찾는다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), null, 3600);
        fixture.seedSession(source, at(10, 30), at(10, 45));

        accountData.merge(source, target);

        assertThat(startsOf(source)).isEmpty();
        assertThat(startsOf(target)).containsExactly(at(10, 0));
    }

    @Test
    void 남는_쪽_세션과_겹치는_draft는_지운다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSession(target, at(10, 0), at(11, 0));
        fixture.seedActiveSession(source, at(10, 30), at(10, 40));
        fixture.seedActiveSession(source, at(12, 0), at(12, 10));

        accountData.merge(source, target);

        assertThat(fixture.count("active_study_session", "user_id", source)).isZero();
        assertThat(fixture.count("active_study_session", "user_id", target)).isEqualTo(1);
    }

    @Test
    void 남는_쪽_draft와_겹치는_draft는_지운다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedActiveSession(target, at(10, 0), at(11, 0));
        fixture.seedActiveSession(source, at(10, 30), at(10, 40));
        fixture.seedActiveSession(source, at(12, 0), at(12, 10));

        accountData.merge(source, target);

        assertThat(fixture.count("active_study_session", "user_id", source)).isZero();
        assertThat(fixture.count("active_study_session", "user_id", target)).isEqualTo(2);
    }

    @Test
    void erase하면_세션과_하위_행이_모두_사라지고_다른_사용자는_그대로다() {
        long user = fixture.seedUser();
        long other = fixture.seedUser();
        long session = fixture.seedSession(user, at(10, 0), at(11, 0));
        fixture.seedStatusEvent(session);
        long subject = fixture.seedSubject(user, "수학", 0, false);
        fixture.seedSegment(session, subject, at(10, 0), at(11, 0));
        fixture.seedActiveSession(user, at(12, 0));
        fixture.seedSession(other, at(10, 0), at(11, 0));
        fixture.seedActiveSession(other, at(12, 0));

        accountData.erase(user);

        assertThat(fixture.count("study_session", "user_id", user)).isZero();
        assertThat(fixture.count("active_study_session", "user_id", user)).isZero();
        assertThat(fixture.count("study_session", "user_id", other)).isEqualTo(1);
        assertThat(fixture.count("active_study_session", "user_id", other)).isEqualTo(1);
    }
}
