package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

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
class RoomUserDataHandlerTest {

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    @Test
    void 병합하면_만든_방과_참가_이력이_남는_쪽으로_옮겨진다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        long room = fixture.seedRoom(source);
        fixture.seedParticipation(room, source, false);

        accountData.merge(source, target);

        assertThat(fixture.count("rooms", "created_by", target)).isEqualTo(1);
        assertThat(fixture.count("room_participations", "user_id", target)).isEqualTo(1);
        assertThat(fixture.countRowsReferencing(source)).isZero();
    }

    @Test
    void 병합_순간_옮기는_쪽이_방에_살아_있으면_먼저_닫고_옮겨_남는_쪽_살아있는_행과_부딪히지_않는다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        long sourceRoom = fixture.seedRoom(source);
        long targetRoom = fixture.seedRoom(target);
        fixture.seedParticipation(sourceRoom, source, true);
        fixture.seedParticipation(targetRoom, target, true);

        accountData.merge(source, target);

        assertThat(jdbc.sql("SELECT count(*) FROM room_participations WHERE user_id = :u AND left_at IS NULL")
                        .param("u", target)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(fixture.count("room_participations", "user_id", target)).isEqualTo(2);
    }

    @Test
    void 만든_방은_탈퇴해도_남고_created_by만_비운다() {
        long user = fixture.seedUser();
        long room = fixture.seedRoom(user);
        fixture.seedParticipation(room, user, false);

        accountData.erase(user);

        assertThat(fixture.count("rooms", "id", room)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT created_by FROM rooms WHERE id = :id")
                        .param("id", room)
                        .query(Long.class)
                        .optional())
                .isEmpty();
        assertThat(fixture.count("room_participations", "user_id", user)).isZero();
    }
}
