package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.common.exception.ErrorCode;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupDetailResponse.PendingApplicationRef;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.PublicGroupKit;
import project.study.group.support.StudyFixture;
import project.study.ranking.engine.StandingsCache;

/**
 * 공개 그룹 보기의 {@code applyState} (BY-1124) — MEMBER > APPLIED > FULL > UNAVAILABLE > CAN_APPLY의 우선순위와 UNAVAILABLE 이유
 * (RECENTLY_KICKED > GROUP_SLOT_LIMIT > APPLICATION_LIMIT)의 우선순위. 기한 지난 신청은 어디에서도 세지 않는다. 시계는 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class PublicGroupApplyStateApiTest {

    private static final Instant FUTURE = NOW.plus(Duration.ofDays(1));
    private static final Instant PAST = NOW.minusSeconds(1);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StandingsCache standingsCache;

    private PublicGroupKit kit;
    private GroupProbe probe;
    private StudyFixture study;
    private long owner;
    private long group;

    @BeforeEach
    void setUp() {
        standingsCache.clear();
        kit = new PublicGroupKit(mvc, jdbc);
        probe = kit.probe;
        study = kit.study;
        owner = kit.user("그룹장", "avatars/owner.jpg");
        group = probe.insertGroup(owner, "PUBLIC", "새벽 공시반 " + UUID.randomUUID(), "CIVIL_SERVICE");
    }

    @AfterEach
    void tearDown() {
        kit.clear();
        standingsCache.clear();
    }

    private PublicGroupDetailResponse detail(long groupId, long userId) {
        return kit.detail(groupId, userId);
    }

    /** 내가 보는 이 테스트의 그룹. */
    private PublicGroupDetailResponse detail() {
        return kit.detail(group, kit.me);
    }

    /** 내가 들어간 다른 그룹 {@code count}개. */
    private void joinOthers(int count) {
        for (int i = 0; i < count; i++) {
            probe.joinNewGroupOf(kit.me);
        }
    }

    /** 내가 다른 그룹에 낸, 답을 기다리는 신청 {@code count}개. */
    private void applyOthers(int count) {
        for (int i = 0; i < count; i++) {
            probe.insertApplicationToNewGroup(kit.me, "PENDING", FUTURE);
        }
    }

    /** 이 그룹을 6명으로 채운다 — 그룹장 포함, 내가 아닌 사람으로. */
    private void fill(long groupId) {
        while (probe.countLiveMembers(groupId) < 6) {
            kit.member(groupId);
        }
    }

    // --- applyState ---

    @Test
    void 신청할_수_있으면_CAN_APPLY이고_이유와_신청_정보는_null이다() {
        // 한도 바로 아래: 그룹 2개 + 신청 2개 = 4칸, 신청 2곳
        joinOthers(2);
        applyOthers(2);

        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.CAN_APPLY);
        assertThat(response.unavailableReason()).isNull();
        assertThat(response.application()).isNull();
    }

    @Test
    void 멤버면_MEMBER이고_다른_조건보다_먼저다() {
        probe.insertMember(group, kit.me, "APPLICATION");
        // 답을 기다리는 신청이 겹쳐 있어도, 6명이 다 차도, 한도에 걸려도 MEMBER다
        probe.insertApplication(group, kit.me, "PENDING", FUTURE);
        fill(group);
        applyOthers(2);

        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.MEMBER);
        assertThat(response.unavailableReason()).isNull();
        assertThat(response.application()).isNull();
    }

    @Test
    void 그룹장도_MEMBER다() {
        assertThat(detail(group, owner).applyState()).isEqualTo(ApplyState.MEMBER);
    }

    @Test
    void 답을_기다리는_신청이_있으면_APPLIED이고_신청_ID와_만료_시각을_준다() {
        long applicationId = probe.insertApplication(group, kit.me, "PENDING", FUTURE);

        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.APPLIED);
        assertThat(response.unavailableReason()).isNull();
        assertThat(response.application()).isEqualTo(new PendingApplicationRef(applicationId, FUTURE));
    }

    @Test
    void APPLIED는_FULL과_한도와_내보낸_기록보다_먼저다() {
        long applicationId = probe.insertApplication(group, kit.me, "PENDING", FUTURE);
        fill(group);
        applyOthers(2); // 신청 3곳 + 그룹 2개로 한도도 걸린다
        joinOthers(2);
        probe.insertLeftMember(group, kit.me, "KICKED", NOW.minus(Duration.ofDays(1)));

        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.APPLIED);
        assertThat(response.application().applicationId()).isEqualTo(applicationId);
    }

    @Test
    void 기한이_지난_신청과_끝난_신청은_APPLIED가_아니다() {
        probe.insertApplication(group, kit.me, "PENDING", PAST);
        probe.insertApplication(group, kit.me, "DECLINED", FUTURE);
        probe.insertApplication(group, kit.me, "CANCELED", FUTURE);

        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.CAN_APPLY);
        assertThat(response.application()).isNull();
    }

    @Test
    void 멤버가_6명이면_FULL이다() {
        fill(group);

        PublicGroupDetailResponse response = detail();

        assertThat(response.memberCount()).isEqualTo(6);
        assertThat(response.applyState()).isEqualTo(ApplyState.FULL);
        assertThat(response.unavailableReason()).isNull();
    }

    @Test
    void FULL은_UNAVAILABLE의_세_이유보다_먼저다() {
        fill(group);
        joinOthers(5);
        probe.insertLeftMember(group, kit.me, "KICKED", NOW.minus(Duration.ofDays(1)));

        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.FULL);
        assertThat(response.unavailableReason()).isNull();
    }

    @Test
    void 내보내진_지_3일이_안_됐으면_UNAVAILABLE_RECENTLY_KICKED다() {
        probe.insertLeftMember(
                group, kit.me, "KICKED", NOW.minus(Duration.ofDays(3)).plusSeconds(1));

        assertUnavailable(ErrorCode.RECENTLY_KICKED);
    }

    @Test
    void 내보내진_지_3일이_지났거나_스스로_나갔으면_내보낸_기록이_아니다() {
        probe.insertLeftMember(group, kit.me, "KICKED", NOW.minus(Duration.ofDays(3)));
        probe.insertLeftMember(group, kit.me, "LEFT", NOW.minus(Duration.ofHours(1)));
        probe.insertLeftMember(group, kit.me, "GROUP_DELETED", NOW.minus(Duration.ofHours(2)));

        assertThat(detail().applyState()).isEqualTo(ApplyState.CAN_APPLY);
    }

    @Test
    void 들어간_그룹과_신청이_합쳐_5개면_UNAVAILABLE_GROUP_SLOT_LIMIT이다() {
        joinOthers(3);
        applyOthers(2);

        assertUnavailable(ErrorCode.GROUP_SLOT_LIMIT);
    }

    @Test
    void 들어간_그룹이_5개여도_GROUP_SLOT_LIMIT이다() {
        joinOthers(5);

        assertUnavailable(ErrorCode.GROUP_SLOT_LIMIT);
    }

    @Test
    void 답을_기다리는_신청이_3곳이면_UNAVAILABLE_APPLICATION_LIMIT이다() {
        applyOthers(3);

        assertUnavailable(ErrorCode.APPLICATION_LIMIT);
    }

    @Test
    void 기한이_지난_신청은_한도에_세지_않는다() {
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(kit.me, "PENDING", PAST);
        }
        joinOthers(4);

        assertThat(detail().applyState()).isEqualTo(ApplyState.CAN_APPLY);
    }

    @Test
    void 이유가_여럿이면_RECENTLY_KICKED가_GROUP_SLOT_LIMIT보다_GROUP_SLOT_LIMIT이_APPLICATION_LIMIT보다_먼저다() {
        // 신청 3곳 + 그룹 2개: 칸 5개도 차고 신청 한도도 찬다 — 칸이 먼저
        joinOthers(2);
        applyOthers(3);
        assertUnavailable(ErrorCode.GROUP_SLOT_LIMIT);

        // 내보내진 지 3일이 안 됐으면 칸·신청 한도가 모두 걸려 있어도 그것이 먼저
        probe.insertLeftMember(group, kit.me, "KICKED", NOW.minus(Duration.ofDays(1)));
        assertUnavailable(ErrorCode.RECENTLY_KICKED);
    }

    private void assertUnavailable(ErrorCode reason) {
        PublicGroupDetailResponse response = detail();

        assertThat(response.applyState()).isEqualTo(ApplyState.UNAVAILABLE);
        assertThat(response.unavailableReason()).isEqualTo(reason);
        assertThat(response.application()).isNull();
    }
}
