package project.study.group.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.category.StudyCategory;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.common.text.Graphemes;
import project.study.group.ContactDetector;
import project.study.group.GroupJoinPolicy;
import project.study.group.GroupRole;
import project.study.group.GroupVisibility;
import project.study.group.InviteCodes;
import project.study.group.dto.GroupCreateRequest;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.dto.GroupUpdateRequest;
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.MyGroupsResponse.GroupLimits;
import project.study.group.dto.MyGroupsResponse.MyGroupCard;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.group.service.GroupAccess.Membership;

/** 그룹 만들기·내 그룹·그룹 홈·설정·알림, 공개 그룹 목록·보기의 입구 (BY-1124). 공개 그룹 읽기는 {@link PublicGroupService}에 있다. */
@Service
@RequiredArgsConstructor
public class GroupService {

    /** 들어간 그룹과 답을 기다리는 신청을 합친 상한 */
    static final int MAX_GROUP_SLOTS = 5;

    /** 동시에 답을 기다릴 수 있는 신청 수 상한 */
    static final int MAX_PENDING_APPLICATIONS = 3;

    /** 그룹장 한도를 없앴지만 이미 배포된 웹이 응답 모양을 기대해서 값만 남긴다. 웹이 안 읽게 되면 지운다. */
    static final int MAX_OWNED_GROUPS = 2;

    /** 그룹 멤버 수 상한 — 신청·수락·코드 참여·바로 입장이 본다 */
    static final int MAX_MEMBERS = 6;

    /** 내보내진 사람이 같은 그룹에 다시 신청·참여할 수 없는 기간 */
    static final Duration KICK_COOLDOWN = Duration.ofDays(3);

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupApplicationRepository applications;
    private final GroupLocks locks;
    private final GroupAccess access;
    private final GroupDeskReader deskReader;
    private final PublicGroupService publicGroupService;
    private final Clock clock;

    /**
     * 그룹을 만들고 ID를 돌려준다. 만든 사람은 멤버이자 그룹장이 되므로 "그룹+신청 5개" 한도를 본다. 공개 그룹이면 같은 이름의 공개 그룹이
     * 이미 있는지도 보고, 둘 다 걸리면 {@code GROUP_SLOT_LIMIT}이 먼저다.
     *
     * <p>사용자 락(락 순서 ②)을 잡은 뒤 센다. 같은 사람이 동시에 만들어도 개수 검사와 insert 사이로 끼어들 수 없다. 아직 없는 그룹이라
     * 그룹 행 락(①)은 없다.
     */
    @Transactional
    public long create(Long userId, GroupCreateRequest request) {
        String name = Graphemes.normalize(request.name());
        String description = normalizeDescription(request.description());
        rejectContact(name, description);
        requireJoinPolicy(request.visibility(), request.joinPolicy());

        Instant now = clock.instant();
        locks.lockUser(userId);
        int slots = members.countLiveGroupsOfUser(userId) + applications.countPendingOfUser(userId, now);
        if (slots >= MAX_GROUP_SLOTS) {
            throw new ConflictException(ErrorCode.GROUP_SLOT_LIMIT, "들어간 그룹과 신청 중인 그룹이 이미 5개예요");
        }

        if (request.visibility() == GroupVisibility.PUBLIC) {
            requirePublicNameFree(name, 0);
        }
        String inviteCode =
                request.visibility() == GroupVisibility.PRIVATE ? InviteCodes.issue(groups::inviteCodeTaken) : null;
        long groupId =
                groups.insert(name, description, request.visibility(), request.category(), userId, inviteCode, now);
        if (request.joinPolicy() == GroupJoinPolicy.OPEN) {
            groups.updateJoinPolicy(groupId, request.joinPolicy());
        }
        members.insert(groupId, userId, GroupMemberRepository.VIA_CREATED, now);
        return groupId;
    }

    /**
     * 내 그룹 탭 — 들어간 순서의 그룹 카드와 신청·그룹장 수, 한도. 책상은 그룹 수와 상관없이 한 번에 읽는다. 멤버 공부 상태가 확정 합계와 draft를 한
     * 스냅샷에서 읽어야 해서 REPEATABLE READ로 연다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyGroupsResponse myGroups(Long userId) {
        Instant now = clock.instant();
        List<Long> groupIds = members.findLiveGroupIdsOfUser(userId);
        Map<Long, GroupRow> live = new HashMap<>();
        groups.findLiveByIds(groupIds).forEach(group -> live.put(group.id(), group));
        // 들어간 순서를 지킨다 — 그룹을 ID 순으로 읽었으므로 소속 순서로 다시 놓는다
        List<GroupRow> joined =
                groupIds.stream().filter(live::containsKey).map(live::get).toList();
        Map<Long, List<GroupMemberDesk>> desks = deskReader.desks(joined, now);
        List<Long> requestableGroupIds = joined.stream()
                .filter(group -> group.ownerId() == userId
                        && group.visibility() == GroupVisibility.PUBLIC
                        && group.joinPolicy() == GroupJoinPolicy.APPROVAL)
                .map(GroupRow::id)
                .toList();
        Map<Long, Integer> pendingCounts = applications.countPendingOfGroups(requestableGroupIds, now);

        List<MyGroupCard> cards = joined.stream()
                .map(group -> {
                    List<GroupMemberDesk> groupDesks = desks.get(group.id());
                    return new MyGroupCard(
                            group.id(),
                            group.name(),
                            group.category(),
                            group.visibility(),
                            group.joinPolicy(),
                            groupDesks.size(),
                            GroupDeskReader.studyingCount(groupDesks),
                            group.ownerId() == userId,
                            requestableGroupIds.contains(group.id()) ? pendingCounts.getOrDefault(group.id(), 0) : null,
                            groupDesks);
                })
                .toList();
        return new MyGroupsResponse(
                cards,
                applications.countPendingOfUser(userId, now),
                groups.countOwnedLive(userId),
                new GroupLimits(MAX_GROUP_SLOTS, MAX_PENDING_APPLICATIONS, MAX_OWNED_GROUPS));
    }

    /**
     * 그룹 홈 — 멤버만. 새 신청 수는 APPROVAL 공개 그룹 그룹장에게만 답을 기다리는(기한 안) 신청 전부를 센다. 내 그룹 탭과 같은 이유로 REPEATABLE READ다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupHomeResponse home(Long userId, long groupId) {
        Instant now = clock.instant();
        Membership membership = access.requireMember(groupId, userId, false);
        GroupRow group = membership.group();
        List<GroupMemberDesk> desks = deskReader.desks(List.of(group), now).get(groupId);
        boolean owner = group.ownerId() == userId;
        Integer newApplicationCount =
                owner && group.visibility() == GroupVisibility.PUBLIC && group.joinPolicy() == GroupJoinPolicy.APPROVAL
                        ? applications.countPendingOfGroup(groupId, now)
                        : null;
        return new GroupHomeResponse(
                group.id(),
                group.name(),
                group.visibility(),
                group.joinPolicy(),
                group.category(),
                group.description(),
                desks.size(),
                owner ? GroupRole.OWNER : GroupRole.MEMBER,
                membership.member().notifyEnabled(),
                newApplicationCount,
                GroupDeskReader.studyingCount(desks),
                desks);
    }

    /** 이름·시험·목표·소개를 통째로 바꾸고 입장 방식은 보냈을 때만 바꾼다(소개를 빼면 지워진다). 그룹장만 할 수 있고, 그룹 행을 잠가 그룹장 넘기기·삭제와 직렬화한다. */
    @Transactional
    public void update(Long userId, long groupId, GroupUpdateRequest request) {
        String name = Graphemes.normalize(request.name());
        String description = normalizeDescription(request.description());
        rejectContact(name, description);

        GroupRow group = access.requireOwner(groupId, userId, true).group();
        GroupJoinPolicy joinPolicy = request.joinPolicy() == null ? group.joinPolicy() : request.joinPolicy();
        requireJoinPolicy(group.visibility(), joinPolicy);
        // 공개 그룹만 이름을 겹치지 못한다. 이름을 안 바꾸는 수정은 이미 겹쳐 있는 그룹이어도 막지 않는다.
        if (group.visibility() == GroupVisibility.PUBLIC && !groups.hasSameNameKey(groupId, name)) {
            requirePublicNameFree(name, groupId);
        }
        Instant now = clock.instant();
        groups.update(groupId, name, request.category(), description, joinPolicy, now);
        if (group.joinPolicy() == GroupJoinPolicy.APPROVAL && joinPolicy == GroupJoinPolicy.OPEN) {
            applications.closePendingForOpenJoin(groupId, now);
        }
    }

    private static void requireJoinPolicy(GroupVisibility visibility, GroupJoinPolicy joinPolicy) {
        if (visibility == GroupVisibility.PRIVATE && joinPolicy == GroupJoinPolicy.OPEN) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "비공개 그룹은 바로 입장을 사용할 수 없어요");
        }
    }

    /**
     * 공개 그룹 이름이 비어 있는지 본다. 이름 락을 쥔 채 세어 같은 이름을 동시에 쓰려는 요청이 사이로 끼어들 수 없다.
     * 락 순서는 만들기 ②→이름, 이름 바꾸기 ①→이름이다({@link GroupLocks}).
     */
    private void requirePublicNameFree(String name, long exceptGroupId) {
        locks.lockPublicName(name);
        if (groups.existsLivePublicNamed(name, exceptGroupId)) {
            throw new ConflictException(ErrorCode.GROUP_NAME_TAKEN, "이미 있는 공개 그룹 이름이에요");
        }
    }

    /** 공개 그룹 목록 — {@link PublicGroupService}가 읽는다. */
    public PublicGroupPageResponse publicGroups(Long userId, String query, StudyCategory category, int page, int size) {
        return publicGroupService.publicGroups(userId, query, category, page, size);
    }

    /** 공개 그룹 보기 — {@link PublicGroupService}가 읽는다. */
    public PublicGroupDetailResponse publicGroup(Long userId, long groupId) {
        return publicGroupService.publicGroup(userId, groupId);
    }

    /** 이 그룹의 멤버 공부 알림을 켜고 끈다. 멤버만 할 수 있고, 같은 값을 다시 보내도 그대로 성공이다. */
    @Transactional
    public void setNotification(Long userId, long groupId, boolean enabled) {
        access.requireMember(groupId, userId, false);
        if (members.setNotify(groupId, userId, enabled) == 0) {
            // 멤버인 것을 확인한 직후 나가거나 내보내진 경우
            throw new ForbiddenException(ErrorCode.NOT_GROUP_MEMBER, "이 그룹의 멤버가 아니에요");
        }
    }

    /**
     * 소개는 비면 null로 저장한다. 공백뿐이거나 보이지 않는 글자(한글 채움 문자 등)만이어도 빈 소개다 — {@code @TextLength}는 소개 최솟값이
     * 0이라 그런 글이 검증을 통과한다.
     */
    private static String normalizeDescription(String raw) {
        String normalized = Graphemes.normalize(raw);
        if (normalized == null || normalized.isEmpty() || Graphemes.isVisuallyBlank(normalized)) {
            return null;
        }
        return normalized;
    }

    private static void rejectContact(String name, String description) {
        if (ContactDetector.containsContact(name) || ContactDetector.containsContact(description)) {
            throw new BadRequestException(ErrorCode.CONTACT_NOT_ALLOWED, "이름이나 소개에 연락처나 링크를 넣을 수 없어요");
        }
    }
}
