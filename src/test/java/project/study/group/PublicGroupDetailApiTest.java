package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupDeskTestConfig.NOW;
import static project.study.group.support.PublicGroupKit.MONDAY_NOON;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import project.study.common.category.StudyCategory;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.PublicGroupKit;
import project.study.group.support.StudyFixture;
import project.study.ranking.engine.StandingsCache;

/**
 * 공개 그룹 보기 (BY-1124) — 필드, 비공개·삭제 404, 책상이 다른 곳을 드러내지 않는 것. 시계는 고정이고, 평균 캐시는 저절로 만료되지 않아 매 테스트
 * 전에 비운다. {@code applyState}는 {@link PublicGroupApplyStateApiTest}가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class PublicGroupDetailApiTest {

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

    // --- 필드 ---

    @Test
    void 멤버가_아니어도_그룹_정보와_그룹장_멤버_책상을_본다() {
        probe.setDescription(group, "새벽 5시에 모여요");
        long first = kit.member(group);
        long second = kit.member(group);
        study.studying(first, 590);

        PublicGroupDetailResponse response = detail();

        assertThat(response.groupId()).isEqualTo(group);
        assertThat(response.name()).isEqualTo(probe.group(group).orElseThrow().name());
        assertThat(response.category()).isEqualTo(StudyCategory.CIVIL_SERVICE);
        assertThat(response.description()).isEqualTo("새벽 5시에 모여요");
        assertThat(response.memberCount()).isEqualTo(3);
        assertThat(response.owner().userId()).isEqualTo(owner);
        assertThat(response.owner().nickname()).isEqualTo(GroupProbe.nickname("그룹장", owner));
        assertThat(response.owner().profileImageUrl()).isEqualTo("/avatars/owner.jpg");
        // 책상은 들어간 순서이고 그룹장 표시가 붙는다
        assertThat(response.members()).extracting(GroupMemberDesk::userId).containsExactly(owner, first, second);
        assertThat(response.members()).extracting(GroupMemberDesk::owner).containsExactly(true, false, false);
        GroupMemberDesk studying = response.members().get(1);
        assertThat(studying.status()).isEqualTo(DeskStatus.STUDYING);
        assertThat(studying.weekFocusSec()).isEqualTo(600);
        assertThat(response.applyState()).isEqualTo(ApplyState.CAN_APPLY);
    }

    @Test
    void 소개가_없으면_null이고_사진이_없는_그룹장도_본다() {
        long plainOwner = kit.user("무사진", null);
        long plainGroup = probe.insertGroup(plainOwner, "PUBLIC");

        PublicGroupDetailResponse response = detail(plainGroup, kit.me);

        assertThat(response.description()).isNull();
        assertThat(response.owner().profileImageUrl()).isNull();
    }

    @Test
    void 합계와_평균은_공개_그룹_목록_카드와_같은_값이다() {
        long member = kit.member(group);
        study.session(member, MONDAY_NOON, 700);
        study.session(owner, MONDAY_NOON, 300);
        String name = probe.group(group).orElseThrow().name();

        PublicGroupDetailResponse response = detail();
        PublicGroupPageResponse page = assertThat(
                        v1Get(mvc, "/api/groups/public", kit.me).queryParam("query", name))
                .hasStatusOk()
                .bodyJson()
                .convertTo(PublicGroupPageResponse.class)
                .actual();

        assertThat(response.weeklyTotalFocusSec()).isEqualTo(1000);
        assertThat(response.weeklyAverageFocusSec()).isEqualTo(500);
        assertThat(page.groups()).hasSize(1);
        assertThat(page.groups().getFirst().weeklyTotalFocusSec()).isEqualTo(1000);
        assertThat(page.groups().getFirst().weeklyAverageFocusSec()).isEqualTo(500);
    }

    // --- 404 ---

    @Test
    void 비공개_그룹은_그룹장과_멤버에게도_404_GROUP_NOT_FOUND다() {
        long privateGroup = probe.insertGroup(owner, "PRIVATE");
        long insider = kit.member(privateGroup);

        for (long viewer : new long[] {kit.me, owner, insider}) {
            assertErrorCode(
                    v1Get(mvc, "/api/groups/public/" + privateGroup, viewer), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
    }

    @Test
    void 지워진_그룹과_없는_그룹은_404_GROUP_NOT_FOUND다() {
        long deleted = probe.insertGroup(owner, "PUBLIC");
        probe.softDeleteGroup(deleted);

        assertErrorCode(v1Get(mvc, "/api/groups/public/" + deleted, kit.me), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        assertErrorCode(v1Get(mvc, "/api/groups/public/987654321", kit.me), HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
    }

    // --- 책상 ---

    @Test
    void 책상은_STUDYING_PAUSED_IDLE만_보이고_순공_필드를_준다() {
        long studying = kit.member(group);
        long paused = kit.member(group);
        long idle = kit.member(group);
        study.studying(studying, 590);
        study.paused(paused, 300);
        study.session(idle, NOW.minusSeconds(3 * 3600), 1800);

        List<GroupMemberDesk> members = detail().members();

        assertThat(members)
                .extracting(GroupMemberDesk::status)
                .containsExactly(DeskStatus.IDLE, DeskStatus.STUDYING, DeskStatus.PAUSED, DeskStatus.IDLE);
        GroupMemberDesk idleDesk = members.get(3);
        assertThat(idleDesk.accumulating()).isFalse();
        assertThat(idleDesk.todayFocusSec()).isEqualTo(1800);
        assertThat(idleDesk.lastStudiedAt())
                .isEqualTo(NOW.minusSeconds(3 * 3600).plusSeconds(1800));
    }

    @Test
    void 다른_공부방에_있는_멤버도_IN_ROOM이_아니라_STUDYING_PAUSED로만_보인다() {
        // IN_ROOM은 이 그룹의 열린 공부방에 확정된 자리가 있을 때만이다(그룹 공부방은 Task 6에서 생긴다). 초대코드 방(싱글룸)에
        // 앉아 있는 멤버는 그 방이 어디인지 드러나지 않고 앱 전체 상태(draft)로만 보인다
        long inSingleRoom = kit.member(group);
        long pausedInOtherRoom = kit.member(group);
        study.studying(inSingleRoom, 590);
        study.paused(pausedInOtherRoom, 300);
        List<Long> roomIds = new ArrayList<>();
        try {
            roomIds.add(openRoom(inSingleRoom));
            roomIds.add(openRoom(pausedInOtherRoom));

            List<GroupMemberDesk> members = detail().members();

            assertThat(members)
                    .extracting(GroupMemberDesk::status)
                    .containsExactly(DeskStatus.IDLE, DeskStatus.STUDYING, DeskStatus.PAUSED);
        } finally {
            closeRooms(roomIds);
        }
    }

    /** 이 사용자가 확정된 자리를 가진 열린 초대코드 방(그룹 방이 아니다)을 만든다. */
    private long openRoom(long userId) {
        String code = UUID.randomUUID().toString().substring(0, 4);
        long roomId = jdbc.sql("insert into rooms (invite_code, created_by, created_at) values (:code, :user, now()) "
                        + "returning id")
                .param("code", code)
                .param("user", userId)
                .query(Long.class)
                .single();
        jdbc.sql("insert into room_participations (room_id, user_id, reserved_at, stomp_confirmed, joined_at) "
                        + "values (:room, :user, now(), true, now())")
                .param("room", roomId)
                .param("user", userId)
                .update();
        return roomId;
    }

    private void closeRooms(List<Long> roomIds) {
        for (long roomId : roomIds) {
            jdbc.sql("delete from room_participations where room_id = :id")
                    .param("id", roomId)
                    .update();
            jdbc.sql("delete from rooms where id = :id").param("id", roomId).update();
        }
    }
}
