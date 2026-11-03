package project.study.group.service;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.category.StudyCategory;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.common.exception.NotImplementedException;
import project.study.common.text.Graphemes;
import project.study.group.ContactDetector;
import project.study.group.GroupVisibility;
import project.study.group.InviteCodes;
import project.study.group.dto.GroupCreateRequest;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupUpdateRequest;
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupRepository;

/** 그룹 만들기·설정·알림은 채웠고, 나머지는 REST 계약만 있는 스텁이다 — 다음 단계에서 채우고 {@link NotImplementedException}을 지운다 (BY-1124). */
@Service
@RequiredArgsConstructor
public class GroupService {

    /** 들어간 그룹과 답을 기다리는 신청을 합친 상한 */
    static final int MAX_GROUP_SLOTS = 5;

    /** 한 사람이 그룹장일 수 있는 그룹 수 상한 */
    static final int MAX_OWNED_GROUPS = 2;

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupApplicationRepository applications;
    private final GroupLocks locks;
    private final GroupAccess access;
    private final Clock clock;

    /**
     * 그룹을 만들고 ID를 돌려준다. 만든 사람은 멤버이자 그룹장이 되므로 "그룹+신청 5개"와 "그룹장 2개" 한도를 모두 본다 — 둘 다 걸리면
     * {@code GROUP_SLOT_LIMIT}이 먼저다.
     *
     * <p>사용자 락(락 순서 ②)을 잡은 뒤 센다. 같은 사람이 동시에 만들어도 개수 검사와 insert 사이로 끼어들 수 없다. 아직 없는 그룹이라
     * 그룹 행 락(①)은 없다.
     */
    @Transactional
    public long create(Long userId, GroupCreateRequest request) {
        String name = Graphemes.normalize(request.name());
        String description = normalizeDescription(request.description());
        rejectContact(name, description);

        Instant now = clock.instant();
        locks.lockUser(userId);
        int slots = members.countLiveGroupsOfUser(userId) + applications.countPendingOfUser(userId, now);
        if (slots >= MAX_GROUP_SLOTS) {
            throw new ConflictException(ErrorCode.GROUP_SLOT_LIMIT, "들어간 그룹과 신청 중인 그룹이 이미 5개예요");
        }
        if (groups.countOwnedLive(userId) >= MAX_OWNED_GROUPS) {
            throw new ConflictException(ErrorCode.OWNED_GROUP_LIMIT, "그룹장인 그룹이 이미 2개예요");
        }

        String inviteCode =
                request.visibility() == GroupVisibility.PRIVATE ? InviteCodes.issue(groups::inviteCodeTaken) : null;
        long groupId =
                groups.insert(name, description, request.visibility(), request.category(), userId, inviteCode, now);
        members.insert(groupId, userId, GroupMemberRepository.VIA_CREATED, now);
        return groupId;
    }

    public MyGroupsResponse myGroups(Long userId) {
        throw new NotImplementedException();
    }

    public GroupHomeResponse home(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    /** 이름·시험·목표·소개를 통째로 바꾼다(소개를 빼면 지워진다). 그룹장만 할 수 있고, 그룹 행을 잠가 그룹장 넘기기·삭제와 직렬화한다. */
    @Transactional
    public void update(Long userId, long groupId, GroupUpdateRequest request) {
        String name = Graphemes.normalize(request.name());
        String description = normalizeDescription(request.description());
        rejectContact(name, description);

        access.requireOwner(groupId, userId, true);
        groups.update(groupId, name, request.category(), description, clock.instant());
    }

    public PublicGroupPageResponse publicGroups(Long userId, String query, StudyCategory category, int page, int size) {
        throw new NotImplementedException();
    }

    public PublicGroupDetailResponse publicGroup(Long userId, long groupId) {
        throw new NotImplementedException();
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
