package project.study.group.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.common.exception.NotFoundException;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;

/**
 * 그룹을 열 자격 검사 (BY-1124). 검사 순서는 그룹 존재(404) → 멤버(403) / 그룹장(403)이다 — 없는 그룹은 누구에게나 404다.
 *
 * <p>{@code lock = true}면 그룹 행을 {@code FOR UPDATE}로 읽어 락 순서 ①을 잡는다. 그룹장 확인·멤버 확인은 잠근 행 기준이라, 같은
 * 순간의 그룹장 넘기기·삭제와 어긋나지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GroupAccess {

    /** 살아 있는 그룹과 그 안의 내 소속. */
    public record Membership(GroupRow group, MemberRow member) {}

    private final GroupRepository groups;
    private final GroupMemberRepository members;

    /** 없거나 삭제된 그룹이면 404 {@code GROUP_NOT_FOUND}. */
    public GroupRow requireLive(long groupId) {
        return groups.findLive(groupId).orElseThrow(GroupAccess::notFound);
    }

    /** 그룹이 없으면 404, 멤버가 아니면 403 {@code NOT_GROUP_MEMBER}. */
    public Membership requireMember(long groupId, long userId, boolean lock) {
        GroupRow group = load(groupId, lock);
        MemberRow member = members.findLive(groupId, userId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.NOT_GROUP_MEMBER, "이 그룹의 멤버가 아니에요"));
        return new Membership(group, member);
    }

    /** 그룹이 없으면 404, 그룹장이 아니면(멤버든 아니든) 403 {@code NOT_GROUP_OWNER}. */
    public Membership requireOwner(long groupId, long userId, boolean lock) {
        GroupRow group = load(groupId, lock);
        if (group.ownerId() != userId) {
            throw new ForbiddenException(ErrorCode.NOT_GROUP_OWNER, "그룹장만 할 수 있어요");
        }
        // 그룹장은 늘 멤버다 — 없다면 데이터가 깨진 것이라 403이 아니라 서버 오류로 드러낸다
        MemberRow member = members.findLive(groupId, userId)
                .orElseThrow(() -> new IllegalStateException("그룹장이 멤버가 아니다 groupId=" + groupId));
        return new Membership(group, member);
    }

    private GroupRow load(long groupId, boolean lock) {
        return (lock ? groups.lockLive(groupId) : groups.findLive(groupId)).orElseThrow(GroupAccess::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException(ErrorCode.GROUP_NOT_FOUND, "없거나 사라진 그룹이에요");
    }
}
