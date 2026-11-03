package project.study.group.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import project.study.block.service.BlockService;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.group.GroupVisibility;
import project.study.group.InviteCodes;
import project.study.group.JoinVia;
import project.study.group.dto.GroupInviteResponse;
import project.study.group.dto.GroupJoinRequest;
import project.study.group.dto.GroupJoinResponse;
import project.study.group.dto.GroupMembersResponse;
import project.study.group.dto.GroupMembersResponse.GroupMemberItem;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.group.dto.ReportRequest;
import project.study.group.push.GroupNotifications;
import project.study.group.report.GroupReports;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupDeskQueries.MemberProfile;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.group.repository.ReportRepository;
import project.study.studysession.dto.MemberStudy;
import project.study.studysession.service.MemberStudySource;
import project.study.user.image.ProfileImageUrls;

/**
 * 그룹 멤버·초대·공부방 자리·멤버 신고 (BY-1124). 공부방 자리 잡기·나가기와 공부방 메시지는 {@link GroupRoomService}에 맡긴다.
 *
 * <p>락 순서({@link GroupLocks}): 그룹을 바꾸는 작업은 모두 그룹 행(①)을 먼저 {@code FOR UPDATE}로 잡는다. 코드 참여만 한도를 세려고 그 뒤
 * 들어오는 사람의 사용자 락(②)을 잡는다. 내보내기·나가기의 공부방 자리 지우기({@link GroupRoomService#memberRemoved})는 ①을 쥔 채
 * 방·자리 행(④⑤)만 잡는다.
 *
 * <p>푸시({@link GroupNotifications})와 공부방 메시지({@link GroupRoomService})는 모두 커밋 뒤에 나간다 — 어느 단계가 실패해 롤백되면
 * 아무것도 나가지 않는다.
 */
@Service
@RequiredArgsConstructor
public class GroupMemberService {

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupApplicationRepository applications;
    private final GroupLocks locks;
    private final GroupAccess access;
    private final GroupDeskQueries deskQueries;
    private final MemberStudySource studySource;
    private final ProfileImageUrls profileImageUrls;
    private final GroupRoomService groupRooms;
    private final GroupNotifications notifications;
    private final BlockService blocks;
    private final GroupReports reports;
    private final Clock clock;

    /**
     * 멤버 목록 — 멤버만, 들어간 순. 마지막 공부 시각은 책상과 같은 {@link MemberStudySource} 값이라, 그쪽이 확정 기록과 draft를 한 스냅샷에서
     * 읽도록 REPEATABLE READ로 연다(그룹 홈과 같은 이유).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupMembersResponse members(Long userId, long groupId) {
        GroupRow group = access.requireMember(groupId, userId, false).group();
        List<MemberRow> rows = members.findLiveByGroups(List.of(groupId));
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(row -> userIds.add(row.userId()));
        Map<Long, MemberProfile> profiles = deskQueries.profiles(userIds);
        Map<Long, MemberStudy> studies = studySource.of(userIds, clock.instant());

        List<GroupMemberItem> items = rows.stream()
                .map(row -> {
                    MemberProfile profile = profiles.get(row.userId());
                    return new GroupMemberItem(
                            row.userId(),
                            profile == null ? null : profile.nickname(),
                            profile == null ? null : profileImageUrls.of(profile.profileImage()),
                            group.ownerId() == row.userId(),
                            row.joinedAt(),
                            studies.get(row.userId()).lastStudiedAt());
                })
                .toList();
        return new GroupMembersResponse(items);
    }

    /**
     * 그룹장을 넘긴다. 순서: 그룹 행 잠금(없음 404, 그룹장 아님 403) → 자기 자신 400 → 대상이 멤버가 아님 404 → 그룹장 바꾸기. 새 그룹장은
     * "그룹장 2개" 한도를 보지 않는다(설계). 커밋 뒤 새 그룹장에게 푸시, 열린 공부방에 {@code OWNER_CHANGED}.
     */
    @Transactional
    public void transferOwner(Long userId, long groupId, long targetUserId) {
        GroupRow group = access.requireOwner(groupId, userId, true).group();
        if (targetUserId == userId) {
            throw new BadRequestException("자기 자신에게는 그룹장을 넘길 수 없어요");
        }
        if (members.findLive(groupId, targetUserId).isEmpty()) {
            throw memberNotFound();
        }
        requireUpdated(groups.updateOwner(groupId, targetUserId, clock.instant()), groupId);
        groupRooms.ownerChanged(groupId, targetUserId);
        notifications.ownerTransferred(targetUserId, groupId, group.name());
    }

    /**
     * 내보낸다. 순서: 그룹 행 잠금(없음 404, 그룹장 아님 403) → 자기 자신 400 → 대상이 멤버가 아님 404 → 소속 끝내기(KICKED) → 비공개면 초대코드
     * 재발급 → 공부방 자리 지우기 → 푸시. 모두 한 트랜잭션이라 어느 단계가 실패해도 전부 롤백되고, 푸시·공부방 메시지는 커밋 뒤에만 나가므로 아무것도
     * 나가지 않는다.
     *
     * <p>비공개 그룹의 코드를 새로 만드는 이유: 내보내진 사람이 예전 코드를 기억했다가 3일 뒤 돌아오지 못하게 한다(설계, FE 요청). 새 코드는 FE가
     * 초대코드 보기를 다시 받아 보여 준다.
     */
    @Transactional
    public void kick(Long userId, long groupId, long targetUserId) {
        GroupRow group = access.requireOwner(groupId, userId, true).group();
        if (targetUserId == userId) {
            throw new BadRequestException("자기 자신은 내보낼 수 없어요");
        }
        Instant now = clock.instant();
        // 그룹 행을 쥐고 있어 멤버 확인과 끝내기 사이에 바뀌지 않는다 — 0건이면 처음부터 멤버가 아니었다
        if (members.markLeft(groupId, targetUserId, GroupMemberRepository.LEAVE_KICKED, now) == 0) {
            throw memberNotFound();
        }
        if (group.visibility() == GroupVisibility.PRIVATE) {
            regenerateInviteCode(group, now);
        }
        groupRooms.memberRemoved(groupId, targetUserId, true);
        notifications.memberKicked(targetUserId, group.name());
    }

    /**
     * 나간다. 순서: 그룹 행 잠금(없음 404, 멤버 아님 403) → 다른 멤버가 있는 그룹장 409 → 소속 끝내기(LEFT) → 공부방 자리 지우기. 혼자 남은
     * 그룹장이었으면 그룹을 지우고(코드도 비운다), 기한 안에서 답을 기다리던 신청을 {@code GROUP_DELETED}로 닫아 그 사람들에게 푸시한다. 기한만
     * 지난 신청은 만료 작업이 닫고 만료 푸시를 보낸다.
     */
    @Transactional
    public void leave(Long userId, long groupId) {
        GroupRow group = access.requireMember(groupId, userId, true).group();
        boolean owner = group.ownerId() == userId;
        if (owner && members.countLive(groupId) > 1) {
            throw new ConflictException(ErrorCode.OWNER_CANNOT_LEAVE, "다른 멤버가 있으면 그룹장을 넘긴 뒤에 나갈 수 있어요");
        }
        Instant now = clock.instant();
        members.markLeft(groupId, userId, GroupMemberRepository.LEAVE_LEFT, now);
        groupRooms.memberRemoved(groupId, userId, false);
        if (!owner) {
            return;
        }
        requireUpdated(groups.softDelete(groupId, now), groupId);
        for (long applicantId : applications.closePendingOfGroup(groupId, now)) {
            notifications.applicationRoomClosed(applicantId, group.name());
        }
    }

    /** 초대코드 보기 — 멤버만(없음 404, 멤버 아님 403). 공개 그룹에는 코드가 없어 400이다. */
    @Transactional(readOnly = true)
    public GroupInviteResponse invite(Long userId, long groupId) {
        GroupRow group = access.requireMember(groupId, userId, false).group();
        requirePrivate(group);
        return new GroupInviteResponse(group.inviteCode());
    }

    /** 코드 새로 만들기 — 그룹장만(없음 404, 그룹장 아님 403), 공개 그룹 400. 그룹 행을 잠근 뒤 바꾸므로 같은 순간의 코드 참여와 엇갈리지 않는다. */
    @Transactional
    public GroupInviteResponse regenerateInvite(Long userId, long groupId) {
        GroupRow group = access.requireOwner(groupId, userId, true).group();
        requirePrivate(group);
        return new GroupInviteResponse(regenerateInviteCode(group, clock.instant()));
    }

    /**
     * 비공개 그룹의 초대코드를 새로 뽑아 바꾸고 새 코드를 돌려준다 — 코드 새로 만들기와 내보내기가 함께 쓰는 한 메서드다. 이전 코드는 이 트랜잭션이 커밋되는
     * 순간 막힌다.
     *
     * <p>호출자가 이 그룹 행을 {@code FOR UPDATE}로 잠근 트랜잭션 안에서만 부른다. 빈 코드를 끝내 못 찾거나(10번 모두 겹침) 같은 순간 다른 그룹이
     * 같은 코드를 가져가 부분 unique 인덱스에 걸리면 예외가 나 호출자의 트랜잭션 전체가 롤백된다.
     */
    public String regenerateInviteCode(GroupRow lockedGroup, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("초대코드 재발급은 그룹 행을 잠근 트랜잭션 안에서만 한다");
        }
        if (lockedGroup.visibility() != GroupVisibility.PRIVATE) {
            throw new IllegalStateException("공개 그룹에는 초대코드가 없다 groupId=" + lockedGroup.id());
        }
        String code = InviteCodes.issue(groups::inviteCodeTaken);
        requireUpdated(groups.updateInviteCode(lockedGroup.id(), code, now), lockedGroup.id());
        return code;
    }

    /**
     * 코드·링크로 들어온다. 순서(Swagger 설명): 코드 찾기(없음·형식 틀림·막힌 코드·사라진 그룹 모두 404) → 그룹 행 잠금 → 잠근 행의 코드를 다시
     * 확인(찾은 뒤 잠그기 전에 재발급됐으면 404) → <b>이미 멤버면 joined=false로 200</b> → 내보낸 지 3일 409 → 6명 409 → 사용자 락(②) →
     * "그룹+신청 5개" 409 → 멤버 넣기.
     *
     * <p>비공개 그룹에는 신청이 없지만, 5개 한도는 다른 그룹에 낸 신청까지 센다. 정원은 그룹 행 락이, 5개 한도는 사용자 락이 직렬화한다.
     */
    @Transactional
    public GroupJoinResponse join(Long userId, GroupJoinRequest request) {
        String code = InviteCodes.canonical(request.inviteCode());
        long groupId =
                groups.findLiveByInviteCode(code).map(GroupRow::id).orElseThrow(GroupMemberService::inviteCodeNotFound);
        // 찾은 뒤 잠그기 전에 코드가 새로 만들어졌거나 그룹이 사라졌으면, 잠근 행에서는 이 코드가 아니다
        groups.lockLive(groupId)
                .filter(locked -> code.equals(locked.inviteCode()))
                .orElseThrow(GroupMemberService::inviteCodeNotFound);

        if (members.findLive(groupId, userId).isPresent()) {
            return new GroupJoinResponse(groupId, false);
        }
        Instant now = clock.instant();
        if (members.recentlyKicked(groupId, userId, now.minus(GroupService.KICK_COOLDOWN))) {
            throw new ConflictException(ErrorCode.RECENTLY_KICKED, "이 그룹에서 내보내진 지 3일이 지나야 다시 들어올 수 있어요");
        }
        if (members.countLive(groupId) >= GroupService.MAX_MEMBERS) {
            throw new ConflictException(ErrorCode.GROUP_FULL, "그룹 멤버가 이미 6명이에요");
        }
        locks.lockUser(userId);
        if (members.countLiveGroupsOfUser(userId) + applications.countPendingOfUser(userId, now)
                >= GroupService.MAX_GROUP_SLOTS) {
            throw new ConflictException(ErrorCode.GROUP_SLOT_LIMIT, "들어간 그룹과 신청 중인 그룹이 이미 5개예요");
        }
        members.insert(groupId, userId, joinedVia(request.via()), now);
        return new GroupJoinResponse(groupId, true);
    }

    public GroupRoomJoinResponse joinRoom(Long userId, long groupId) {
        return groupRooms.join(userId, groupId);
    }

    public void leaveRoom(Long userId, long groupId) {
        groupRooms.leave(userId, groupId);
    }

    /**
     * 공부방 멤버를 신고한다 — 신고하면 차단도 된다. 순서(Swagger): 그룹 없음 404 → 멤버 아님 403 → 자기 자신 400 → 대상이 멤버가 아님 404
     * {@code GROUP_MEMBER_NOT_FOUND} → 차단(이미 있으면 그대로) → 두 사람이 같은 그룹 공부방에 있으면 커밋 뒤 각자에게 {@code PEER_BLOCKED} → 신고
     * 기록·운영 알림. 그룹 멤버 자격·책상·순위는 그대로다.
     *
     * <p>그룹을 바꾸지 않으므로 그룹 행을 잠그지 않는다. 같은 순간 대상이 내보내져도 신고는 그때의 멤버를 신고한 것으로 남는다(그룹은 지워져도 행이
     * 남아 신고의 그룹 참조가 깨지지 않는다).
     */
    @Transactional
    public void reportMember(Long userId, long groupId, long targetUserId, ReportRequest request) {
        access.requireMember(groupId, userId, false);
        if (targetUserId == userId) {
            throw new BadRequestException("자기 자신은 신고할 수 없어요");
        }
        if (members.findLive(groupId, targetUserId).isEmpty()) {
            throw memberNotFound();
        }
        Instant now = clock.instant();
        blocks.block(userId, targetUserId, now);
        groupRooms.peerBlocked(userId, targetUserId);
        reports.record(ReportRepository.ROOM_MEMBER, userId, targetUserId, groupId, null, request.reason(), now);
    }

    private static String joinedVia(JoinVia via) {
        return switch (via) {
            case LINK -> GroupMemberRepository.VIA_INVITE_LINK;
            case CODE -> GroupMemberRepository.VIA_INVITE_CODE;
        };
    }

    private static void requirePrivate(GroupRow group) {
        if (group.visibility() != GroupVisibility.PRIVATE) {
            throw new BadRequestException("공개 그룹에는 초대코드가 없어요");
        }
    }

    /** 그룹 행을 잠근 채 바꾸므로 0건은 일어나지 않는다 — 일어나면 데이터가 깨진 것이라 롤백한다. */
    private static void requireUpdated(int updated, long groupId) {
        if (updated == 0) {
            throw new IllegalStateException("잠근 그룹 행을 바꾸지 못했다 groupId=" + groupId);
        }
    }

    private static NotFoundException memberNotFound() {
        return new NotFoundException(ErrorCode.GROUP_MEMBER_NOT_FOUND, "이 그룹의 멤버가 아니에요");
    }

    private static NotFoundException inviteCodeNotFound() {
        return new NotFoundException(ErrorCode.INVITE_CODE_NOT_FOUND, "없거나 막힌 초대코드예요");
    }
}
