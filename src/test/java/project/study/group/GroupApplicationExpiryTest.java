package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PushAssert.noPush;
import static project.study.group.support.PushAssert.pushedOnceTo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.study.TestcontainersConfiguration;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.scheduler.GroupApplicationExpiryScheduler;
import project.study.group.service.GroupApplicationExpiry;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.ApplicationRow;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/** 3일 만료 작업 (BY-1124) — 바꾼 행에만 푸시, 두 번 돌아도 한 번, 경계는 "기한 <= 지금". 서비스 메서드를 직접 부른다. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupApplicationExpiryTest {

    private static final Instant FUTURE = NOW.plus(1, ChronoUnit.DAYS);

    @Autowired
    private GroupApplicationExpiry expiry;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private GroupApplicationRepository applications;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private long group;
    private long user;

    @BeforeEach
    void setUp() {
        // 앞선 테스트가 남긴 기한 지난 신청을 푸시 없이 치운다 — expireDue는 DB 전체를 보므로 남아 있으면 건수·푸시 단언이 어긋난다
        jdbc.sql("update study_group_application set status = 'EXPIRED' "
                        + "where status = 'PENDING' and expires_at <= :now")
                .param("now", Timestamp.from(NOW))
                .update();
        probe = new GroupProbe(jdbc);
        group = probe.insertGroup(probe.insertUser(), "PUBLIC");
        user = probe.insertUser();
    }

    private String statusOf(long applicationId) {
        return probe.application(applicationId).orElseThrow().status();
    }

    @Test
    void 기한이_된_신청은_EXPIRED로_바뀌고_신청한_사람에게_만료_푸시가_간다() {
        long id = probe.insertApplication(group, user, "PENDING", NOW.minusSeconds(30));

        int changed = expiry.expireDue(NOW);

        assertThat(changed).isEqualTo(1);
        ApplicationRow row = probe.application(id).orElseThrow();
        assertThat(row.status()).isEqualTo("EXPIRED");
        assertThat(row.answeredAt()).isEqualTo(NOW);
        PushMessage message = pushedOnceTo(pushSender, user);
        assertThat(message.type()).isEqualTo("group_application_expired");
        assertThat(message.title()).isEqualTo("다른 그룹에 신청해 볼까요?");
        assertThat(message.body()).isEqualTo(probe.group(group).orElseThrow().name() + " 신청은 답이 없어 취소됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public");
    }

    @Test
    void 경계는_기한이_지금_이전이거나_같을_때다() {
        long exact = probe.insertApplication(group, user, "PENDING", NOW);
        long second = probe.insertApplication(group, probe.insertUser(), "PENDING", NOW.plusSeconds(1));

        assertThat(expiry.expireDue(NOW)).isEqualTo(1);

        assertThat(statusOf(exact)).isEqualTo("EXPIRED");
        assertThat(statusOf(second)).isEqualTo("PENDING");
    }

    @Test
    void 이미_답했거나_기한_안인_신청은_건드리지_않고_푸시도_없다() {
        long fresh = probe.insertApplication(group, user, "PENDING", FUTURE);
        long accepted = probe.insertApplication(group, probe.insertUser(), "ACCEPTED", NOW.minusSeconds(30));
        long canceled = probe.insertApplication(group, probe.insertUser(), "CANCELED", NOW.minusSeconds(30));

        assertThat(expiry.expireDue(NOW)).isZero();

        assertThat(statusOf(fresh)).isEqualTo("PENDING");
        assertThat(statusOf(accepted)).isEqualTo("ACCEPTED");
        assertThat(statusOf(canceled)).isEqualTo("CANCELED");
        noPush(pushSender);
    }

    @Test
    void 두_번_돌아도_푸시는_한_번이다() {
        probe.insertApplication(group, user, "PENDING", NOW.minusSeconds(30));

        assertThat(expiry.expireDue(NOW)).isEqualTo(1);
        assertThat(expiry.expireDue(NOW.plusSeconds(60))).isZero();

        assertThat(pushedOnceTo(pushSender, user).type()).isEqualTo("group_application_expired");
    }

    @Test
    void 행마다_그_신청한_사람에게_한_번씩_보낸다() {
        long second = probe.insertUser();
        long otherGroup = probe.insertGroup(probe.insertUser(), "PUBLIC");
        probe.insertApplication(group, user, "PENDING", NOW.minusSeconds(30));
        probe.insertApplication(group, second, "PENDING", NOW.minusSeconds(20));
        probe.insertApplication(otherGroup, user, "PENDING", NOW.minusSeconds(10));

        assertThat(expiry.expireDue(NOW)).isEqualTo(3);

        // user는 두 그룹에 낸 신청이 모두 만료돼 푸시 두 건을 받고, second는 한 건이다
        PushMessage forSecond = pushedOnceTo(pushSender, second);
        assertThat(forSecond.body()).contains(probe.group(group).orElseThrow().name());
        verify(pushSender, timeout(3000).times(2)).send(eq(List.of(user)), any());
    }

    @Test
    void 사라진_그룹의_기한_지난_신청도_EXPIRED로_닫고_만료_푸시를_보낸다() {
        long gone = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long id = probe.insertApplication(gone, user, "PENDING", NOW.minusSeconds(30));
        probe.softDeleteGroup(gone);

        assertThat(expiry.expireDue(NOW)).isEqualTo(1);

        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        PushMessage message = pushedOnceTo(pushSender, user);
        assertThat(message.type()).isEqualTo("group_application_expired");
        assertThat(message.body()).isEqualTo(probe.group(gone).orElseThrow().name() + " 신청은 답이 없어 취소됐어요");
    }

    @Test
    void 그룹장이_탈퇴해_그룹장이_빈_사라진_그룹의_신청도_만료_푸시를_보낸다() {
        long gone = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long id = probe.insertApplication(gone, user, "PENDING", NOW.minusSeconds(30));
        probe.softDeleteGroup(gone);
        // 탈퇴가 하는 일 — 지운 그룹은 그룹장을 비운다(BY-1126, V43)
        jdbc.sql("UPDATE study_group SET owner_id = NULL WHERE id = :id")
                .param("id", gone)
                .update();

        assertThat(expiry.expireDue(NOW)).isEqualTo(1);

        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(pushedOnceTo(pushSender, user).body())
                .isEqualTo(probe.group(gone).orElseThrow().name() + " 신청은 답이 없어 취소됐어요");
    }

    @Test
    void 그룹이_사라질_때_닫히지_않은_기한_지난_신청은_만료_작업이_EXPIRED로_닫고_만료_푸시를_보낸다() {
        long gone = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long lapsed = probe.insertApplication(gone, user, "PENDING", NOW.minusSeconds(30));
        long waiting = probe.insertApplication(gone, probe.insertUser(), "PENDING", FUTURE);

        // 그룹 삭제 단계가 하는 일 — 답을 기다리던 신청만 GROUP_DELETED로 닫는다
        assertThat(applications.closePendingOfGroup(gone, NOW)).hasSize(1);
        probe.softDeleteGroup(gone);
        assertThat(expiry.expireDue(NOW)).isEqualTo(1);

        assertThat(statusOf(lapsed)).isEqualTo("EXPIRED");
        assertThat(statusOf(waiting)).isEqualTo("GROUP_DELETED");
        assertThat(pushedOnceTo(pushSender, user).type()).isEqualTo("group_application_expired");
    }

    @Test
    void 테스트에서는_스케줄러_빈이_꺼져_있다() {
        assertThat(context.getBeansOfType(GroupApplicationExpiryScheduler.class))
                .isEmpty();
    }
}
