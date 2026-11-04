package project.study.block;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.block.service.BlockService;
import project.study.group.report.ReportAlert;
import project.study.group.support.BlockProbe;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.push.service.PushSender;

/**
 * 차단 목록·해제 (BY-1124)와 다른 기능이 쓰는 차단 조회. 차단은 공부방 멤버 신고로만 생기므로 상황은 SQL로 세운다. 신고·차단 테스트는 모두
 * 같은 설정(고정 시계, 푸시·신고 알림 목)이라 컨텍스트를 하나만 띄운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class BlockApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private BlockService blockService;

    @MockitoBean
    private PushSender pushSender;

    @MockitoBean
    private ReportAlert reportAlert;

    private GroupProbe probe;
    private BlockProbe blocks;
    private long me;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        blocks = new BlockProbe(jdbc);
        me = probe.insertUser("나", null);
    }

    @Test
    void 차단_목록은_내가_건_차단만_오래된_것부터_닉네임_사진과_함께_준다() {
        long later = probe.insertUser("나중", null);
        long earlier = probe.insertUser("먼저", "avatars/earlier.webp");
        long blocksMe = probe.insertUser("나를차단", null);
        blocks.insertBlock(me, later, NOW.minus(Duration.ofHours(1)));
        blocks.insertBlock(me, earlier, NOW.minus(Duration.ofHours(2)));
        blocks.insertBlock(blocksMe, me, NOW.minus(Duration.ofHours(3)));

        assertThat(v1Get(mvc, "/api/blocks", me)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"blockedUsers": [
                          {"userId": %d, "nickname": "%s", "profileImageUrl": "/avatars/earlier.webp",
                           "blockedAt": "2026-10-14T04:00:00Z"},
                          {"userId": %d, "nickname": "%s", "profileImageUrl": null,
                           "blockedAt": "2026-10-14T05:00:00Z"}
                        ]}""".formatted(
                        earlier, GroupProbe.nickname("먼저", earlier), later, GroupProbe.nickname("나중", later)));
    }

    @Test
    void 차단한_사람이_없으면_빈_배열이다() {
        assertThat(v1Get(mvc, "/api/blocks", me)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"blockedUsers": []}""");
    }

    @Test
    void 해제는_내가_건_차단만_지우고_상대가_건_차단은_남는다() {
        long other = probe.insertUser("상대", null);
        blocks.insertBlock(me, other, NOW);
        blocks.insertBlock(other, me, NOW);

        assertThat(v1Delete(mvc, "/api/blocks/" + other, me)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(blocks.blockedAt(me, other)).isEmpty();
        assertThat(blocks.blockedAt(other, me)).contains(NOW);
        assertThat(blockService.blockedEitherWay(me, other))
                .as("상대가 건 차단으로 계속 막힌다")
                .isTrue();
    }

    @Test
    void 차단하지_않은_사람이나_없는_사람을_해제해도_204다() {
        long stranger = probe.insertUser();

        assertThat(v1Delete(mvc, "/api/blocks/" + stranger, me)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(v1Delete(mvc, "/api/blocks/" + stranger, me)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(v1Delete(mvc, "/api/blocks/987654321", me)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 이미_차단한_사이를_다시_차단하면_처음_차단_시각이_그대로다() {
        long other = probe.insertUser();
        blocks.insertBlock(me, other, NOW.minus(Duration.ofDays(1)));

        blockService.block(me, other, NOW);

        assertThat(blocks.blockedAt(me, other)).contains(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void 차단_사이는_어느_쪽이_걸었든_양쪽에서_보인다() {
        long blockedByMe = probe.insertUser();
        long blockerOfMe = probe.insertUser();
        long free = probe.insertUser();
        long blockedElsewhere = probe.insertUser();
        blocks.insertBlock(me, blockedByMe, NOW);
        blocks.insertBlock(blockerOfMe, me, NOW);
        blocks.insertBlock(free, blockedElsewhere, NOW);

        assertThat(blockService.blockedEitherWay(me, blockedByMe)).isTrue();
        assertThat(blockService.blockedEitherWay(blockedByMe, me)).isTrue();
        assertThat(blockService.blockedEitherWay(me, blockerOfMe)).isTrue();
        assertThat(blockService.blockedEitherWay(me, free)).isFalse();
        assertThat(blockService.blockedPeers(me, List.of(blockedByMe, blockerOfMe, free, blockedElsewhere)))
                .containsExactlyInAnyOrder(blockedByMe, blockerOfMe);
        assertThat(blockService.blockedPeers(me, List.of(free))).isEmpty();
        assertThat(blockService.blockedPeers(me, List.of())).isEmpty();
    }
}
