package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupDeskTestConfig.KST;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.GroupRow;
import project.study.group.support.QueryCounter;
import project.study.group.support.StudyFixture;

/** 그룹 홈 — 멤버만, 그룹 정보와 멤버 책상(들어간 순서, 앱 전체 공부 상태·순공) (BY-1124). 시계는 2026-10-14(수) 15:00 KST로 고정이다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class GroupDeskApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private GroupProbe probe;
    private StudyFixture study;
    private long owner;
    private long member;
    private long groupId;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        study = new StudyFixture(jdbc);
        owner = probe.insertUser("그룹장", "avatars/owner.jpg");
        member = probe.insertUser("멤버", null);
        groupId = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(groupId, member, "APPLICATION");
    }

    private static Instant kst(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, KST).toInstant();
    }

    private GroupHomeResponse home(long userId, long id) {
        return assertThat(v1Get(mvc, "/api/groups/" + id, userId))
                .hasStatusOk()
                .bodyJson()
                .convertTo(GroupHomeResponse.class)
                .actual();
    }

    @Test
    void 그룹_정보와_멤버_책상을_들어간_순서로_준다() {
        long third = probe.insertUser("셋째", null);
        probe.insertMember(groupId, third, "INVITE_CODE");
        GroupRow group = probe.group(groupId).orElseThrow();

        GroupHomeResponse home = home(member, groupId);

        assertThat(home.groupId()).isEqualTo(groupId);
        assertThat(home.name()).isEqualTo(group.name());
        assertThat(home.visibility()).isEqualTo(GroupVisibility.PUBLIC);
        assertThat(home.category().name()).isEqualTo(group.category());
        assertThat(home.description()).isNull();
        assertThat(home.memberCount()).isEqualTo(3);
        assertThat(home.members()).extracting(GroupMemberDesk::userId).containsExactly(owner, member, third);
        assertThat(home.members()).extracting(GroupMemberDesk::owner).containsExactly(true, false, false);
        assertThat(home.members())
                .extracting(GroupMemberDesk::nickname)
                .containsExactly(
                        GroupProbe.nickname("그룹장", owner),
                        GroupProbe.nickname("멤버", member),
                        GroupProbe.nickname("셋째", third));
        // 사진 키는 응답 경로로 바뀐다(테스트 base-url은 비어 있어 같은 출처 상대 경로), 없으면 null
        assertThat(home.members().get(0).profileImageUrl()).isEqualTo("/avatars/owner.jpg");
        assertThat(home.members().get(1).profileImageUrl()).isNull();
    }

    @Test
    void 내_역할과_알림_설정은_내_소속_기준이다() {
        probe.setNotify(groupId, member, true);

        GroupHomeResponse asMember = home(member, groupId);
        GroupHomeResponse asOwner = home(owner, groupId);

        assertThat(asMember.myRole()).isEqualTo(GroupRole.MEMBER);
        assertThat(asMember.notifyEnabled()).isTrue();
        assertThat(asOwner.myRole()).isEqualTo(GroupRole.OWNER);
        assertThat(asOwner.notifyEnabled()).isFalse();
    }

    @Test
    void 공개_그룹_그룹장에게만_기한_안_신청_수를_준다() {
        probe.insertApplication(groupId, probe.insertUser(), "PENDING", NOW.plusSeconds(3600));
        probe.insertApplication(groupId, probe.insertUser(), "PENDING", NOW.plusSeconds(60));
        probe.insertApplication(groupId, probe.insertUser(), "PENDING", NOW.minusSeconds(1)); // 기한 지남 — 만료 작업 전
        probe.insertApplication(groupId, probe.insertUser(), "DECLINED", NOW.plusSeconds(3600));
        long otherGroup = probe.insertGroup(owner, "PUBLIC");
        probe.insertApplication(otherGroup, probe.insertUser(), "PENDING", NOW.plusSeconds(3600)); // 다른 그룹

        assertThat(home(owner, groupId).newApplicationCount()).isEqualTo(2);
        assertThat(home(member, groupId).newApplicationCount()).isNull();
    }

    @Test
    void 비공개_그룹은_그룹장에게도_신청_수가_null이다() {
        long privateGroup = probe.insertGroup(owner, "PRIVATE");

        GroupHomeResponse home = home(owner, privateGroup);

        assertThat(home.visibility()).isEqualTo(GroupVisibility.PRIVATE);
        assertThat(home.newApplicationCount()).isNull();
    }

    @Test
    void 책상은_앱_전체_공부_상태와_오늘_이번_주_순공을_준다() {
        study.session(owner, kst(14, 10, 0), 1800); // 오늘 30분, 10:30에 끝
        study.session(owner, kst(12, 9, 0), 1200); // 이번 주 월요일
        study.session(owner, kst(11, 9, 0), 3600); // 지난주
        study.studying(member, 590); // 집중 중 — NOW까지 늘려 600초

        GroupHomeResponse home = home(member, groupId);

        GroupMemberDesk ownerDesk = home.members().get(0);
        assertThat(ownerDesk.status()).isEqualTo(DeskStatus.IDLE);
        assertThat(ownerDesk.accumulating()).isFalse();
        assertThat(ownerDesk.todayFocusSec()).isEqualTo(1800);
        assertThat(ownerDesk.weekFocusSec()).isEqualTo(3000);
        assertThat(ownerDesk.lastStudiedAt()).isEqualTo(kst(14, 10, 30));

        GroupMemberDesk memberDesk = home.members().get(1);
        assertThat(memberDesk.status()).isEqualTo(DeskStatus.STUDYING);
        assertThat(memberDesk.accumulating()).isTrue();
        assertThat(memberDesk.todayFocusSec()).isEqualTo(600);
        assertThat(memberDesk.weekFocusSec()).isEqualTo(600);
        assertThat(memberDesk.lastStudiedAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    void 공부_중인_멤버_수는_STUDYING만_센다() {
        long pausedMember = probe.insertUser("쉬는중", null);
        probe.insertMember(groupId, pausedMember, "APPLICATION");
        study.studying(member, 590);
        study.paused(pausedMember, 300);

        GroupHomeResponse home = home(owner, groupId);

        assertThat(home.members())
                .extracting(GroupMemberDesk::status)
                .containsExactly(DeskStatus.IDLE, DeskStatus.STUDYING, DeskStatus.PAUSED);
        assertThat(home.members()).extracting(GroupMemberDesk::accumulating).containsExactly(false, true, false);
        // IN_ROOM은 그룹 공부방 자리가 생기는 단계에서 함께 센다 — 지금은 이 그룹 공부방이 없다
        assertThat(home.studyingCount()).isEqualTo(1);
    }

    @Test
    void 나간_멤버는_책상에_없다() {
        long left = probe.insertUser("나간사람", null);
        probe.insertMember(groupId, left, "APPLICATION");
        probe.leaveGroup(groupId, left);

        GroupHomeResponse home = home(owner, groupId);

        assertThat(home.memberCount()).isEqualTo(2);
        assertThat(home.members()).extracting(GroupMemberDesk::userId).containsExactly(owner, member);
    }

    @Test
    void 멤버가_아니면_403_NOT_GROUP_MEMBER다() {
        long stranger = probe.insertUser();
        long left = probe.insertUser();
        probe.insertMember(groupId, left, "APPLICATION");
        probe.leaveGroup(groupId, left);

        assertErrorCode(v1Get(mvc, "/api/groups/" + groupId, stranger), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
        assertErrorCode(v1Get(mvc, "/api/groups/" + groupId, left), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
    }

    @Test
    void 없거나_지워진_그룹은_404_GROUP_NOT_FOUND다() {
        long deleted = probe.insertGroup(owner, "PUBLIC");
        probe.softDeleteGroup(deleted);

        assertErrorCode(v1Get(mvc, "/api/groups/" + deleted, owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(v1Get(mvc, "/api/groups/987654321", owner), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    @Test
    void 멤버가_늘어도_쿼리_수는_그대로다() {
        study.studying(member, 590);
        int twoMembers = QueryCounter.count(() -> home(owner, groupId));

        for (int i = 0; i < 4; i++) {
            long more = probe.insertUser("멤버" + i, "avatars/" + i + ".jpg");
            probe.insertMember(groupId, more, "APPLICATION");
            study.session(more, kst(14, 9, i), 600);
            study.studying(more, 120);
        }
        int sixMembers = QueryCounter.count(() -> home(owner, groupId));

        assertThat(twoMembers).isPositive();
        assertThat(sixMembers).isEqualTo(twoMembers);
    }
}
