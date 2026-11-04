package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.TestcontainersConfiguration;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupApplicationRepository.ApplicationRow;
import project.study.group.support.GroupProbe;

/** 신청 리포지토리의 조건부 갱신 의미 (BY-1124) — 건수, 기한 안/지난 구분, 그룹이 사라질 때 닫기. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupApplicationRepositoryTest {

    private static final Instant FUTURE = NOW.plus(1, ChronoUnit.DAYS);
    private static final Instant PAST = NOW.minusSeconds(1);

    @Autowired
    private GroupApplicationRepository applications;

    @Autowired
    private JdbcClient jdbc;

    private GroupProbe probe;
    private long group;
    private long user;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        group = probe.insertGroup(probe.insertUser(), "PUBLIC");
        user = probe.insertUser();
    }

    @Test
    void insert는_답을_기다리는_신청을_만들고_행을_그대로_읽을_수_있다() {
        long id = applications.insert(group, user, "안녕하세요", NOW, FUTURE);

        ApplicationRow row = applications.find(id).orElseThrow();
        assertThat(row.groupId()).isEqualTo(group);
        assertThat(row.userId()).isEqualTo(user);
        assertThat(row.introduction()).isEqualTo("안녕하세요");
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.expiresAt()).isEqualTo(FUTURE);
        assertThat(row.answeredAt()).isNull();
        assertThat(applications.lock(id)).contains(row);
    }

    @Test
    void 같은_쌍에_답을_기다리는_신청을_하나_더_넣으면_부분_unique가_막는다() {
        applications.insert(group, user, "안녕하세요", NOW, FUTURE);

        assertThatThrownBy(() -> applications.insert(group, user, "또요", NOW, FUTURE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 없는_신청은_빈_값이다() {
        assertThat(applications.find(987_654_321L)).isEmpty();
        assertThat(applications.lock(987_654_321L)).isEmpty();
    }

    @Test
    void findPending은_기한_안의_답을_기다리는_신청만_찾는다() {
        long fresh = probe.insertApplication(group, user, "PENDING", FUTURE);
        long other = probe.insertUser();
        probe.insertApplication(group, other, "PENDING", PAST);
        long done = probe.insertUser();
        probe.insertApplication(group, done, "DECLINED", FUTURE);

        assertThat(applications.findPending(group, user, NOW))
                .map(ApplicationRow::id)
                .contains(fresh);
        assertThat(applications.findPending(group, other, NOW)).isEmpty();
        assertThat(applications.findPending(group, done, NOW)).isEmpty();
        // 기한이 딱 지금이면 이미 지난 것이다
        assertThat(applications.findPending(group, user, FUTURE)).isEmpty();
    }

    @Test
    void answer는_출발_상태가_맞을_때만_바꾸고_건수를_돌려준다() {
        long id = probe.insertApplication(group, user, "PENDING", FUTURE);

        assertThat(applications.answer(id, "ACCEPTED", "DECLINED", NOW)).isZero();
        assertThat(probe.application(id).orElseThrow().status()).isEqualTo("PENDING");

        assertThat(applications.answer(id, "PENDING", "CANCELED", NOW)).isEqualTo(1);
        ApplicationRow row = applications.find(id).orElseThrow();
        assertThat(row.status()).isEqualTo("CANCELED");
        assertThat(row.answeredAt()).isEqualTo(NOW);

        // 한 번 답한 신청은 다시 답할 수 없다
        assertThat(applications.answer(id, "PENDING", "ACCEPTED", NOW)).isZero();
    }

    @Test
    void expireStale은_그_쌍의_기한_지난_신청만_바꾼다() {
        long stale = probe.insertApplication(group, user, "PENDING", PAST);
        long otherUser = probe.insertUser();
        long otherStale = probe.insertApplication(group, otherUser, "PENDING", PAST);
        long otherGroup = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long otherGroupStale = probe.insertApplication(otherGroup, user, "PENDING", PAST);

        assertThat(applications.expireStale(group, user, NOW)).isEqualTo(1);

        assertThat(probe.application(stale).orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(probe.application(stale).orElseThrow().answeredAt()).isEqualTo(NOW);
        assertThat(probe.application(otherStale).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(probe.application(otherGroupStale).orElseThrow().status()).isEqualTo("PENDING");
        // 같은 행을 두 번 바꾸지 않는다 — 두 번째는 0건이라 푸시도 한 번뿐이다
        assertThat(applications.expireStale(group, user, NOW)).isZero();
    }

    @Test
    void expireStale은_기한_안_신청과_끝난_신청을_건드리지_않는다() {
        long fresh = probe.insertApplication(group, user, "PENDING", FUTURE);

        assertThat(applications.expireStale(group, user, NOW)).isZero();

        assertThat(probe.application(fresh).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void closePendingOfGroup은_기한_안에서_답을_기다리던_신청만_GROUP_DELETED로_닫고_사람_ID를_돌려준다() {
        long a = probe.insertUser();
        long b = probe.insertUser();
        long lapsed = probe.insertUser();
        long idA = probe.insertApplication(group, a, "PENDING", FUTURE);
        long idB = probe.insertApplication(group, b, "PENDING", FUTURE.plusSeconds(5));
        long idLapsed = probe.insertApplication(group, lapsed, "PENDING", PAST);
        long declined = probe.insertApplication(group, probe.insertUser(), "DECLINED", FUTURE);
        long otherGroup = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long otherPending = probe.insertApplication(otherGroup, a, "PENDING", FUTURE);

        List<Long> closed = applications.closePendingOfGroup(group, NOW);

        assertThat(closed).containsExactlyInAnyOrder(a, b);
        for (long id : List.of(idA, idB)) {
            assertThat(probe.application(id).orElseThrow().status()).isEqualTo("GROUP_DELETED");
            assertThat(probe.application(id).orElseThrow().answeredAt()).isEqualTo(NOW);
        }
        // 기한이 지난 신청은 닫지도 돌려주지도 않는다 — 이미 만료된 신청이라 만료 푸시를 받아야 한다
        assertThat(probe.application(idLapsed).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(probe.application(declined).orElseThrow().status()).isEqualTo("DECLINED");
        assertThat(probe.application(otherPending).orElseThrow().status()).isEqualTo("PENDING");
        // 다시 불러도 바꿀 것이 없다
        assertThat(applications.closePendingOfGroup(group, NOW)).isEmpty();
    }

    @Test
    void closePendingOfGroup은_기한이_지난_신청을_건드리지_않는다_그건_만료_작업_몫이다() {
        long lapsed = probe.insertUser();
        long idLapsed = probe.insertApplication(group, lapsed, "PENDING", PAST);
        // 기한이 딱 지금인 신청도 이미 지난 것이다
        long exact = probe.insertUser();
        long idExact = probe.insertApplication(group, exact, "PENDING", NOW);

        assertThat(applications.closePendingOfGroup(group, NOW)).isEmpty();

        for (long id : List.of(idLapsed, idExact)) {
            assertThat(probe.application(id).orElseThrow().status()).isEqualTo("PENDING");
            assertThat(probe.application(id).orElseThrow().answeredAt()).isNull();
        }
        // 이 행들은 만료 작업이 EXPIRED로 닫는다
        assertThat(applications.expireStale(group, lapsed, NOW)).isEqualTo(1);
        assertThat(probe.application(idLapsed).orElseThrow().status()).isEqualTo("EXPIRED");
    }
}
