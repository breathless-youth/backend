package project.study.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import project.study.block.service.BlockService;
import project.study.common.category.StudyCategory;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.group.GroupJoinPolicy;
import project.study.group.GroupVisibility;
import project.study.group.dto.GroupCreateRequest;
import project.study.group.dto.GroupUpdateRequest;
import project.study.group.push.GroupNotifications;
import project.study.group.report.GroupReports;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.studysession.service.MemberStudySource;
import project.study.user.image.ProfileImageUrls;

/** 입장 방식 검증·설정 변경과 바로 입장의 락 순서를 단위 검증한다 (BY-1124). */
class GroupJoinPolicyServiceTest {

    private static final long GROUP = 10L;
    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    private final GroupRepository groups = mock(GroupRepository.class);
    private final GroupMemberRepository members = mock(GroupMemberRepository.class);
    private final GroupApplicationRepository applications = mock(GroupApplicationRepository.class);
    private final GroupLocks locks = mock(GroupLocks.class);
    private final GroupAccess access = mock(GroupAccess.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final GroupService service = new GroupService(
            groups,
            members,
            applications,
            locks,
            access,
            mock(GroupDeskReader.class),
            mock(PublicGroupService.class),
            clock);
    private final GroupMemberService memberService = new GroupMemberService(
            groups,
            members,
            applications,
            locks,
            access,
            mock(GroupDeskQueries.class),
            mock(MemberStudySource.class),
            mock(ProfileImageUrls.class),
            mock(GroupRoomService.class),
            mock(GroupOwnership.class),
            mock(GroupNotifications.class),
            mock(BlockService.class),
            mock(GroupReports.class),
            clock);

    @Test
    void 비공개_바로_입장_생성은_락이나_저장_전에_검증_실패다() {
        GroupCreateRequest request =
                new GroupCreateRequest("비공개", GroupVisibility.PRIVATE, StudyCategory.ETC, null, GroupJoinPolicy.OPEN);
        assertThatThrownBy(() -> service.create(USER, request))
                .isInstanceOfSatisfying(
                        BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(groups, members, applications, locks);
    }

    @Test
    void 설정은_그룹을_잠근_다음_바꾸고_대기_신청을_닫는다() {
        owned(GroupJoinPolicy.APPROVAL);
        service.update(USER, GROUP, update(GroupJoinPolicy.OPEN));

        var order = inOrder(access, groups, applications);
        order.verify(access).requireOwner(GROUP, USER, true);
        order.verify(groups).hasSameNameKey(GROUP, "내 그룹");
        order.verify(groups).update(GROUP, "내 그룹", StudyCategory.ETC, null, GroupJoinPolicy.OPEN, NOW);
        order.verify(applications).closePendingForOpenJoin(GROUP, NOW);
        verifyNoInteractions(members);
    }

    @Test
    void 바로_입장에서_신청_후_입장으로_바꾸거나_유지하면_멤버와_신청을_건드리지_않는다() {
        owned(GroupJoinPolicy.OPEN);
        service.update(USER, GROUP, update(GroupJoinPolicy.APPROVAL));
        service.update(USER, GROUP, update(null));
        verify(groups).update(GROUP, "내 그룹", StudyCategory.ETC, null, GroupJoinPolicy.APPROVAL, NOW);
        verify(groups).update(GROUP, "내 그룹", StudyCategory.ETC, null, GroupJoinPolicy.OPEN, NOW);
        verifyNoInteractions(members, applications);
    }

    @Test
    void 공개_이름이_겹치면_입장_방식_저장과_신청_닫기를_하지_않는다() {
        owned(GroupJoinPolicy.APPROVAL);
        when(groups.hasSameNameKey(GROUP, "내 그룹")).thenReturn(false);
        when(groups.existsLivePublicNamed("내 그룹", GROUP)).thenReturn(true);
        assertThatThrownBy(() -> service.update(USER, GROUP, update(GroupJoinPolicy.OPEN)))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.GROUP_NAME_TAKEN));
        verify(groups, never()).update(anyLong(), anyString(), any(), any(), any(), any());
        verifyNoInteractions(applications, members);
    }

    @Test
    void 바로_입장은_그룹_락_다음_사용자_락을_잡고_한도를_센_뒤_넣는다() {
        when(groups.lockLive(GROUP)).thenReturn(Optional.of(group(GroupJoinPolicy.OPEN)));
        when(members.countLive(GROUP)).thenReturn(1);
        when(members.countLiveGroupsOfUser(USER)).thenReturn(1);
        when(applications.countPendingOfUser(USER, NOW)).thenReturn(3);
        assertThat(memberService.joinOpen(USER, GROUP).joined()).isTrue();

        var order = inOrder(groups, members, applications, locks);
        order.verify(groups).lockLive(GROUP);
        order.verify(members).findLive(GROUP, USER);
        order.verify(members).recentlyKicked(GROUP, USER, NOW.minus(GroupService.KICK_COOLDOWN));
        order.verify(members).countLive(GROUP);
        order.verify(locks).lockUser(USER);
        order.verify(members).countLiveGroupsOfUser(USER);
        order.verify(applications).countPendingOfUser(USER, NOW);
        order.verify(members).insert(GROUP, USER, GroupMemberRepository.VIA_OPEN_JOIN, NOW);
    }

    @Test
    void 신청_후_입장_그룹은_멤버_검사_전에_막는다() {
        when(groups.lockLive(GROUP)).thenReturn(Optional.of(group(GroupJoinPolicy.APPROVAL)));
        assertThatThrownBy(() -> memberService.joinOpen(USER, GROUP))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.GROUP_JOIN_APPROVAL_REQUIRED));
        verifyNoInteractions(members, applications, locks);
    }

    private void owned(GroupJoinPolicy policy) {
        when(access.requireOwner(GROUP, USER, true)).thenReturn(new GroupAccess.Membership(group(policy), null));
        when(groups.hasSameNameKey(GROUP, "내 그룹")).thenReturn(true);
    }

    private static GroupRow group(GroupJoinPolicy policy) {
        return new GroupRow(
                GROUP, "내 그룹", null, GroupVisibility.PUBLIC, policy, StudyCategory.ETC, USER, null, NOW, NOW, null);
    }

    private static GroupUpdateRequest update(GroupJoinPolicy policy) {
        return new GroupUpdateRequest("내 그룹", StudyCategory.ETC, null, policy);
    }
}
