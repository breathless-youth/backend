package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.race;
import static project.study.group.support.GroupApiTestSupport.status;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.GroupInviteResponse;
import project.study.group.dto.GroupJoinResponse;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.MemberRow;
import project.study.push.service.PushSender;

/**
 * 초대코드 보기·새로 만들기와 코드·링크로 들어오기 (BY-1124). 검사 순서는 Swagger 설명이 원본이다 — 코드 확인(404) → 이미 멤버면 joined=false →
 * 내보낸 지 3일 → 6명 → 5개 한도. 시계는 고정이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupInviteApiTest {

    /** 발급 문자에 없는 0이 들어 있어 어떤 그룹의 코드도 될 수 없다 */
    private static final String NEVER_ISSUED = "K7M2P0";

    private static final Duration THREE_DAYS = Duration.ofDays(3);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private long owner;
    private long member;
    private long group;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        owner = probe.insertUser("그룹장", null);
        member = probe.insertUser("멤버", null);
        group = probe.insertGroup(owner, "PRIVATE");
        probe.insertMember(group, member, "INVITE_CODE");
    }

    private String code(long groupId) {
        return probe.group(groupId).orElseThrow().inviteCode();
    }

    private MockMvcRequestBuilder joinRequest(long userId, String inviteCode, String via) {
        return json(v1Post(mvc, "/api/groups/join", userId), """
                {"inviteCode": "%s", "via": "%s"}""".formatted(inviteCode, via));
    }

    private GroupJoinResponse join(long userId, String inviteCode) {
        return assertThat(joinRequest(userId, inviteCode, "CODE"))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupJoinResponse.class)
                .actual();
    }

    private void joinFails(long userId, String inviteCode, HttpStatus status, String code) {
        assertErrorCode(joinRequest(userId, inviteCode, "CODE"), status, code);
    }

    private GroupInviteResponse invite(long userId, long groupId) {
        return assertThat(v1Get(mvc, "/api/groups/" + groupId + "/invite", userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupInviteResponse.class)
                .actual();
    }

    private void fill(long groupId, int size) {
        while (probe.countLiveMembers(groupId) < size) {
            probe.insertMember(groupId, probe.insertUser(), "APPLICATION");
        }
    }

    /** 다른 사람이 그룹장인 그룹 {@code count}개에 넣는다. */
    private void joinOtherGroups(long userId, int count) {
        for (int i = 0; i < count; i++) {
            probe.joinNewGroupOf(userId);
        }
    }

    private boolean isMember(long groupId, long userId) {
        return probe.members(groupId).stream().anyMatch(row -> row.userId() == userId && row.leftAt() == null);
    }

    // --- 초대코드 보기·새로 만들기 ---

    @Test
    void 초대코드_보기는_멤버에게_지금_코드를_준다() {
        assertThat(invite(member, group).inviteCode()).isEqualTo(code(group));
        assertThat(invite(owner, group).inviteCode()).isEqualTo(code(group));
    }

    @Test
    void 초대코드_보기는_비멤버_403_공개_그룹_400_없는_그룹_404다() {
        long publicGroup = probe.insertGroup(owner, "PUBLIC");

        assertErrorCode(
                v1Get(mvc, "/api/groups/" + group + "/invite", probe.insertUser()),
                HttpStatus.FORBIDDEN,
                "NOT_GROUP_MEMBER");
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + publicGroup + "/invite", owner), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        assertErrorCode(v1Get(mvc, "/api/groups/987654321/invite", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    @Test
    void 코드를_새로_만들면_새_코드를_주고_이전_코드는_바로_막힌다() {
        String old = code(group);

        GroupInviteResponse created = assertThat(v1Post(mvc, "/api/groups/" + group + "/invite", owner))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupInviteResponse.class)
                .actual();

        assertThat(created.inviteCode())
                .isNotEqualTo(old)
                .matches("[" + InviteCodes.ALPHABET + "]{6}")
                .isEqualTo(code(group));
        assertThat(probe.group(group).orElseThrow().updatedAt()).isEqualTo(NOW);
        joinFails(probe.insertUser(), old, HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND");
        assertThat(join(probe.insertUser(), created.inviteCode()).joined()).isTrue();
    }

    @Test
    void 코드_새로_만들기는_그룹장만_하고_공개_그룹은_400이다() {
        String old = code(group);
        long publicGroup = probe.insertGroup(owner, "PUBLIC");

        assertErrorCode(
                v1Post(mvc, "/api/groups/" + group + "/invite", member), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        assertErrorCode(
                v1Post(mvc, "/api/groups/" + publicGroup + "/invite", owner), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        assertErrorCode(v1Post(mvc, "/api/groups/987654321/invite", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");

        assertThat(code(group)).isEqualTo(old);
        assertThat(probe.group(publicGroup).orElseThrow().inviteCode()).isNull();
    }

    // --- 코드·링크로 들어오기 ---

    @Test
    void 코드는_대소문자와_앞뒤_공백을_가리지_않고_들어온_경로를_남긴다() {
        long byCode = probe.insertUser();
        long byLink = probe.insertUser();

        GroupJoinResponse joined = join(byCode, "  " + code(group).toLowerCase() + " ");
        assertThat(joinRequest(byLink, code(group), "LINK")).hasStatusOk();

        assertThat(joined.groupId()).isEqualTo(group);
        assertThat(joined.joined()).isTrue();
        List<MemberRow> rows = probe.members(group);
        assertThat(rows)
                .filteredOn(row -> row.userId() == byCode)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.joinedVia()).isEqualTo("INVITE_CODE");
                    assertThat(row.notifyEnabled()).isFalse();
                });
        assertThat(rows)
                .filteredOn(row -> row.userId() == byLink)
                .singleElement()
                .extracting(MemberRow::joinedVia)
                .isEqualTo("INVITE_LINK");
    }

    @Test
    void 없는_코드_틀린_형식_사라진_그룹의_코드는_모두_404_INVITE_CODE_NOT_FOUND다() {
        long stranger = probe.insertUser();
        long deleted = probe.insertGroup(owner, "PRIVATE");
        String deletedCode = code(deleted);
        probe.softDeleteGroup(deleted);

        joinFails(stranger, NEVER_ISSUED, HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND");
        joinFails(stranger, "12", HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND");
        joinFails(stranger, deletedCode, HttpStatus.NOT_FOUND, "INVITE_CODE_NOT_FOUND");
    }

    @Test
    void 이미_멤버면_다_찬_그룹이고_한도가_찼어도_200_joined_false다() {
        fill(group, 6);
        joinOtherGroups(member, 4); // 이 그룹까지 5개

        GroupJoinResponse response = join(member, code(group));

        assertThat(response.groupId()).isEqualTo(group);
        assertThat(response.joined()).isFalse();
        assertThat(probe.members(group))
                .filteredOn(row -> row.userId() == member)
                .hasSize(1);
    }

    @Test
    void 멤버가_6명이면_409_GROUP_FULL이다() {
        fill(group, 6);
        long stranger = probe.insertUser();

        joinFails(stranger, code(group), HttpStatus.CONFLICT, "GROUP_FULL");

        assertThat(probe.countLiveMembers(group)).isEqualTo(6);
    }

    @Test
    void 들어간_그룹과_답을_기다리는_신청이_5개면_409_GROUP_SLOT_LIMIT다() {
        long full = probe.insertUser();
        joinOtherGroups(full, 4);
        probe.insertApplicationToNewGroup(full, "PENDING", NOW.plusSeconds(60));
        long staleApplicant = probe.insertUser();
        joinOtherGroups(staleApplicant, 4);
        // 기한이 지난 신청은 만료 작업 전이라도 세지 않는다
        probe.insertApplicationToNewGroup(staleApplicant, "PENDING", NOW.minusSeconds(1));

        joinFails(full, code(group), HttpStatus.CONFLICT, "GROUP_SLOT_LIMIT");
        assertThat(join(staleApplicant, code(group)).joined()).isTrue();

        assertThat(isMember(group, full)).isFalse();
    }

    @Test
    void 내보내진_지_3일이_안_됐으면_409_RECENTLY_KICKED이고_3일이_지나면_들어온다() {
        long recent = probe.insertUser();
        long longAgo = probe.insertUser();
        long leftByThemselves = probe.insertUser();
        probe.insertLeftMember(group, recent, "KICKED", NOW.minus(THREE_DAYS).plusSeconds(1));
        probe.insertLeftMember(group, longAgo, "KICKED", NOW.minus(THREE_DAYS));
        probe.insertLeftMember(group, leftByThemselves, "LEFT", NOW.minusSeconds(60));

        joinFails(recent, code(group), HttpStatus.CONFLICT, "RECENTLY_KICKED");
        assertThat(join(longAgo, code(group)).joined()).isTrue();
        assertThat(join(leftByThemselves, code(group)).joined()).isTrue();
    }

    @Test
    void 검사_순서는_내보냄_정원_한도_순이다() {
        long kickedAndBusy = probe.insertUser();
        probe.insertLeftMember(group, kickedAndBusy, "KICKED", NOW.minusSeconds(60));
        joinOtherGroups(kickedAndBusy, 5);
        long busy = probe.insertUser();
        joinOtherGroups(busy, 5);
        fill(group, 6);

        joinFails(kickedAndBusy, code(group), HttpStatus.CONFLICT, "RECENTLY_KICKED");
        joinFails(busy, code(group), HttpStatus.CONFLICT, "GROUP_FULL");
    }

    @Test
    void 다섯_명인_그룹에_두_사람이_동시에_들어오면_한_사람만_된다() throws Exception {
        fill(group, 5);
        String inviteCode = code(group);
        long first = probe.insertUser();
        long second = probe.insertUser();

        List<Callable<Integer>> tasks = List.of(
                () -> status(joinRequest(first, inviteCode, "CODE")),
                () -> status(joinRequest(second, inviteCode, "LINK")));
        List<Integer> statuses = race(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(probe.countLiveMembers(group)).isEqualTo(6);
        long loser = statuses.get(0) == 409 ? first : second;
        joinFails(loser, inviteCode, HttpStatus.CONFLICT, "GROUP_FULL");
    }

    @Test
    void 한_사람이_두_그룹에_동시에_들어와도_5개를_넘지_않는다() throws Exception {
        long busy = probe.insertUser();
        joinOtherGroups(busy, 4);
        long otherGroup = probe.insertGroup(owner, "PRIVATE");
        String firstCode = code(group);
        String secondCode = code(otherGroup);

        List<Integer> statuses = race(List.of(
                () -> status(joinRequest(busy, firstCode, "CODE")),
                () -> status(joinRequest(busy, secondCode, "CODE"))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(isMember(group, busy) ^ isMember(otherGroup, busy)).isTrue();
    }
}
