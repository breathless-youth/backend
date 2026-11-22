package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.study.TestcontainersConfiguration;
import project.study.group.repository.GroupAlertRepository;
import project.study.group.repository.GroupAlertRepository.StudyStart;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.StartAlertFixture;
import project.study.push.service.PushSender;

/** 여러 서버가 그룹방 공부 시작을 가져갈 때 자리별로 한 번만 처리하는지 검증한다. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupStudyStartClaimTest {

    @Autowired
    private GroupAlertRepository alerts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private StartAlertFixture fixture;
    private long starter;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        fixture = new StartAlertFixture(jdbc, probe);
        starter = probe.insertUser();
        group = probe.insertGroup(starter, "PUBLIC");
    }

    @AfterEach
    void tearDown() {
        fixture.closeRooms();
    }

    private void startStudy(long userId) {
        fixture.seatInGroupRoom(group, userId, true, false);
        jdbc.sql("""
                        UPDATE room_participations SET camera_on = true, stomp_session_id = :sessionId
                         WHERE user_id = :userId AND left_at IS NULL""")
                .param("sessionId", "session-" + userId)
                .param("userId", userId)
                .update();
    }

    @Test
    void 시작을_꺼낸_뒤_같은_방으로_다시_들어와도_이전_자리는_알리지_않는다() {
        startStudy(starter);
        List<StudyStart> claimed = alerts.claimStudyStarts();
        assertThat(claimed).hasSize(1);

        fixture.leaveSeat(starter);
        startStudy(starter);

        assertThat(alerts.findStudyGroup(claimed.getFirst())).isEmpty();
        assertThat(alerts.claimStudyStarts())
                .singleElement()
                .extracting(StudyStart::starterId)
                .isEqualTo(starter);
    }

    @Test
    void 가져온_시작을_반납하면_공부를_재개한_뒤_다시_가져갈_수_있다() {
        startStudy(starter);
        StudyStart start = alerts.claimStudyStarts().getFirst();
        jdbc.sql("UPDATE room_participations SET stomp_confirmed = false, camera_on = false WHERE id = :id")
                .param("id", start.participationId())
                .update();

        assertThat(alerts.findStudyGroup(start).orElseThrow().studying()).isFalse();
        alerts.releaseStudyStart(start.participationId());
        assertThat(alerts.claimStudyStarts()).isEmpty();

        jdbc.sql("UPDATE room_participations SET stomp_confirmed = true, camera_on = true WHERE id = :id")
                .param("id", start.participationId())
                .update();
        assertThat(alerts.claimStudyStarts()).containsExactly(start);
    }

    @Test
    void 다른_트랜잭션이_잠근_자리는_기다리지_않고_다음에_꺼낸다() throws Exception {
        startStudy(starter);
        long free = probe.insertUser();
        probe.insertMember(group, free, "APPLICATION");
        startStudy(free);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock =
                    holder.prepareStatement("select id from room_participations where user_id = ? for update")) {
                lock.setLong(1, starter);
                lock.executeQuery().close();

                List<StudyStart> claimed = assertTimeoutPreemptively(Duration.ofSeconds(5), alerts::claimStudyStarts);
                assertThat(claimed).extracting(StudyStart::starterId).containsExactly(free);
            } finally {
                holder.rollback();
            }
        }

        assertThat(alerts.claimStudyStarts()).extracting(StudyStart::starterId).containsExactly(starter);
    }

    @Test
    void 두_서버가_동시에_가져가도_자리는_한_번만_나온다() throws Exception {
        startStudy(starter);
        long second = probe.insertUser();
        probe.insertMember(group, second, "APPLICATION");
        startStudy(second);

        CountDownLatch go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<List<StudyStart>>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    go.await();
                    return alerts.claimStudyStarts();
                }));
            }
            go.countDown();

            List<StudyStart> claimed = new ArrayList<>();
            for (Future<List<StudyStart>> result : results) {
                claimed.addAll(result.get());
            }
            assertThat(claimed).extracting(StudyStart::starterId).containsExactlyInAnyOrder(starter, second);
        }
    }
}
