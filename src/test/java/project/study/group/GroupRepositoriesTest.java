package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.common.category.StudyCategory;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.common.exception.NotFoundException;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.group.service.GroupAccess;
import project.study.group.support.GroupProbe;

/** 이후 단계가 기대는 리포지토리·접근 검사의 의미 (BY-1124) — 순서, 살아 있는 행만 보기, 갱신 건수, 검사 순서. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupRepositoriesTest {

    @Autowired
    private GroupRepository groups;

    @Autowired
    private GroupMemberRepository members;

    @Autowired
    private GroupApplicationRepository applications;

    @Autowired
    private GroupAccess access;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    private GroupProbe probe;
    private long owner;
    private long other;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        owner = probe.insertUser();
        other = probe.insertUser();
    }

    @Test
    void 그룹_행은_enum과_시각까지_그대로_읽힌다() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        long id = groups.insert("공부방", null, GroupVisibility.PRIVATE, StudyCategory.JOB, owner, "K7M2PQ", now);

        GroupRow row = groups.findLive(id).orElseThrow();
        assertThat(row.visibility()).isEqualTo(GroupVisibility.PRIVATE);
        assertThat(row.category()).isEqualTo(StudyCategory.JOB);
        assertThat(row.ownerId()).isEqualTo(owner);
        assertThat(row.description()).isNull();
        assertThat(row.createdAt()).isEqualTo(now);
        assertThat(row.deletedAt()).isNull();
    }

    @Test
    void 공개_그룹에_초대코드를_넣으면_제약이_막는다() {
        assertThatThrownBy(() -> groups.insert(
                        "공부방", null, GroupVisibility.PUBLIC, StudyCategory.JOB, owner, "K7M2PQ", Instant.now()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 지운_그룹은_읽기에서_빠지고_코드를_다시_쓸_수_있다() {
        long id = probe.insertGroup(owner, "PRIVATE");
        String code = groups.findLive(id).orElseThrow().inviteCode();
        assertThat(groups.inviteCodeTaken(code)).isTrue();
        assertThat(groups.findLiveByInviteCode(code)).map(GroupRow::id).contains(id);

        assertThat(groups.softDelete(id, Instant.now())).isEqualTo(1);
        assertThat(groups.softDelete(id, Instant.now())).as("두 번째는 0건").isZero();

        assertThat(groups.findLive(id)).isEmpty();
        assertThat(groups.lockLive(id)).isEmpty();
        assertThat(groups.findLiveByIds(List.of(id))).isEmpty();
        // 지운 그룹의 이름이 필요한 곳(기한 지난 신청의 만료 푸시)은 삭제 표시된 그룹까지 읽는다
        assertThat(groups.findIncludingDeletedByIds(List.of(id)))
                .extracting(GroupRow::id)
                .containsExactly(id);
        assertThat(groups.findIncludingDeletedByIds(List.of())).isEmpty();
        assertThat(groups.findLiveByInviteCode(code)).isEmpty();
        assertThat(groups.inviteCodeTaken(code)).isFalse();
        assertThat(groups.countOwnedLive(owner)).isZero();
        assertThat(groups.update(id, "x", StudyCategory.ETC, null, Instant.now()))
                .isZero();
    }

    @Test
    void 코드를_바꾸면_이전_코드는_더_이상_찾아지지_않는다() {
        long id = probe.insertGroup(owner, "PRIVATE");
        String oldCode = groups.findLive(id).orElseThrow().inviteCode();

        assertThat(groups.updateInviteCode(id, "ZZZZZZ", Instant.now())).isEqualTo(1);

        assertThat(groups.findLiveByInviteCode(oldCode)).isEmpty();
        assertThat(groups.findLiveByInviteCode("ZZZZZZ")).map(GroupRow::id).contains(id);
    }

    @Test
    void 그룹장을_바꾸면_그룹장_수에_반영된다() {
        long id = probe.insertGroup(owner, "PUBLIC");
        assertThat(groups.countOwnedLive(owner)).isEqualTo(1);

        assertThat(groups.updateOwner(id, other, Instant.now())).isEqualTo(1);

        assertThat(groups.countOwnedLive(owner)).isZero();
        assertThat(groups.countOwnedLive(other)).isEqualTo(1);
    }

    @Test
    void 멤버는_그룹_안에서_들어온_순서로_나온다() {
        long groupId = probe.insertGroup(owner, "PUBLIC");
        long third = probe.insertUser();
        probe.insertMember(groupId, other, "APPLICATION");
        probe.insertMember(groupId, third, "INVITE_CODE");

        List<MemberRow> rows = members.findLiveByGroups(List.of(groupId));

        assertThat(rows).extracting(MemberRow::userId).containsExactly(owner, other, third);
        assertThat(members.countLive(groupId)).isEqualTo(3);
        assertThat(members.findLiveByGroups(List.of())).isEmpty();
    }

    @Test
    void 내가_들어간_그룹은_들어간_순서로_나오고_나간_그룹은_빠진다() {
        long first = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long second = probe.insertGroup(probe.insertUser(), "PUBLIC");
        long third = probe.insertGroup(probe.insertUser(), "PUBLIC");
        probe.insertMember(second, owner, "APPLICATION");
        probe.insertMember(first, owner, "APPLICATION");
        probe.insertMember(third, owner, "INVITE_LINK");

        assertThat(members.findLiveGroupIdsOfUser(owner)).containsExactly(second, first, third);
        assertThat(members.countLiveGroupsOfUser(owner)).isEqualTo(3);

        assertThat(members.markLeft(first, owner, GroupMemberRepository.LEAVE_LEFT, Instant.now()))
                .isEqualTo(1);
        assertThat(members.markLeft(first, owner, GroupMemberRepository.LEAVE_LEFT, Instant.now()))
                .as("이미 나갔으면 0건")
                .isZero();

        assertThat(members.findLiveGroupIdsOfUser(owner)).containsExactly(second, third);
        assertThat(members.findLive(first, owner)).isEmpty();
    }

    @Test
    void 나갔다_다시_들어오면_새_행이다() {
        long groupId = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(groupId, other, "APPLICATION");
        members.markLeft(groupId, other, GroupMemberRepository.LEAVE_LEFT, Instant.now());

        members.insert(groupId, other, GroupMemberRepository.VIA_INVITE_CODE, Instant.now());

        assertThat(probe.members(groupId)).hasSize(3);
        assertThat(members.findLive(groupId, other)).isPresent();
    }

    @Test
    void 내보낸_지_3일_안인지는_KICKED_행의_나간_시각으로_본다() {
        long groupId = probe.insertGroup(owner, "PUBLIC");
        Instant now = Instant.now();
        Instant since = now.minus(3, ChronoUnit.DAYS);
        probe.insertLeftMember(groupId, other, "KICKED", now.minus(2, ChronoUnit.DAYS));
        long longAgo = probe.insertUser();
        probe.insertLeftMember(groupId, longAgo, "KICKED", now.minus(4, ChronoUnit.DAYS));
        long left = probe.insertUser();
        probe.insertLeftMember(groupId, left, "LEFT", now.minus(1, ChronoUnit.HOURS));

        assertThat(members.recentlyKicked(groupId, other, since)).isTrue();
        assertThat(members.recentlyKicked(groupId, longAgo, since)).isFalse();
        assertThat(members.recentlyKicked(groupId, left, since))
                .as("스스로 나간 것은 해당 없음")
                .isFalse();
        assertThat(members.recentlyKicked(groupId + 1, other, since))
                .as("다른 그룹")
                .isFalse();
    }

    @Test
    void 답을_기다리는_신청은_기한이_안_지난_PENDING만_센다() {
        Instant now = Instant.now();
        probe.insertApplicationToNewGroup(owner, "PENDING", now.plusSeconds(1));
        probe.insertApplicationToNewGroup(owner, "PENDING", now.minusSeconds(1));
        probe.insertApplicationToNewGroup(owner, "ACCEPTED", now.plusSeconds(60));
        probe.insertApplicationToNewGroup(owner, "CANCELED", now.plusSeconds(60));
        probe.insertApplicationToNewGroup(other, "PENDING", now.plusSeconds(60));

        assertThat(applications.countPendingOfUser(owner, now)).isEqualTo(1);
    }

    @Test
    void 없는_그룹은_누구에게나_404이고_그_뒤에_그룹장_멤버를_가린다() {
        long groupId = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(groupId, other, "APPLICATION");
        long stranger = probe.insertUser();

        assertThat(access.requireOwner(groupId, owner, false).group().id()).isEqualTo(groupId);
        assertThat(access.requireMember(groupId, other, false).member().userId())
                .isEqualTo(other);
        assertThat(access.requireLive(groupId).id()).isEqualTo(groupId);

        assertThatThrownBy(() -> access.requireOwner(groupId, other, false))
                .isInstanceOfSatisfying(
                        ForbiddenException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_GROUP_OWNER));
        assertThatThrownBy(() -> access.requireOwner(groupId, stranger, false))
                .isInstanceOfSatisfying(
                        ForbiddenException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_GROUP_OWNER));
        assertThatThrownBy(() -> access.requireMember(groupId, stranger, false))
                .isInstanceOfSatisfying(
                        ForbiddenException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_GROUP_MEMBER));

        probe.softDeleteGroup(groupId);
        for (long user : List.of(owner, other, stranger)) {
            assertThatThrownBy(() -> access.requireOwner(groupId, user, false))
                    .isInstanceOfSatisfying(
                            NotFoundException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.GROUP_NOT_FOUND));
            assertThatThrownBy(() -> access.requireMember(groupId, user, false))
                    .isInstanceOfSatisfying(
                            NotFoundException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.GROUP_NOT_FOUND));
        }
        assertThatThrownBy(() -> access.requireLive(groupId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 잠그고_읽는_경로도_같은_결과를_준다() {
        long groupId = probe.insertGroup(owner, "PUBLIC");

        GroupAccess.Membership membership = tx.execute(status -> access.requireOwner(groupId, owner, true));

        assertThat(membership.group().id()).isEqualTo(groupId);
        assertThat(membership.member().userId()).isEqualTo(owner);
    }
}
