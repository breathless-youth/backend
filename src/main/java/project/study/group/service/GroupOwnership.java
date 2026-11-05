package project.study.group.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;

/**
 * 그룹장이 바뀌는 일과 혼자 남은 그룹장이 떠나 그룹이 사라지는 일 (BY-1124·BY-1126). 그룹장 넘기기·나가기({@link GroupMemberService})와
 * 탈퇴({@link GroupUserDataHandler})가 같은 처리를 하도록 한 곳에 둔다.
 *
 * <p>호출자가 이 그룹 행을 {@code FOR UPDATE}로 잠근 트랜잭션 안에서만 부른다. 푸시·공부방 메시지는 모두 커밋 뒤에 나간다.
 */
@Component
@RequiredArgsConstructor
public class GroupOwnership {

    private final GroupRepository groups;
    private final GroupApplicationRepository applications;
    private final GroupRoomService groupRooms;
    private final GroupNotifications notifications;

    /** 그룹장을 바꾼다. 커밋 뒤 새 그룹장에게 푸시, 열린 공부방에 {@code OWNER_CHANGED}. 새 그룹장의 "그룹장 2개" 한도는 보지 않는다. */
    public void handOver(GroupRow lockedGroup, long newOwnerId, Instant now) {
        requireUpdated(groups.updateOwner(lockedGroup.id(), newOwnerId, now), lockedGroup.id());
        groupRooms.ownerChanged(lockedGroup.id(), newOwnerId);
        notifications.ownerTransferred(newOwnerId, lockedGroup.id(), lockedGroup.name());
    }

    /**
     * 혼자 남은 그룹장이 떠난 그룹을 지운다(코드도 비운다). 기한 안에서 답을 기다리던 신청을 {@code GROUP_DELETED}로 닫아 그 사람들에게 커밋 뒤
     * 푸시한다. 기한만 지난 신청은 만료 작업이 닫고 만료 푸시를 보낸다. 그룹장의 소속 끝내기와 공부방 자리 지우기는 호출자가 한다.
     */
    public void closeAbandoned(GroupRow lockedGroup, Instant now) {
        requireUpdated(groups.softDelete(lockedGroup.id(), now), lockedGroup.id());
        for (long applicantId : applications.closePendingOfGroup(lockedGroup.id(), now)) {
            notifications.applicationRoomClosed(applicantId, lockedGroup.name());
        }
    }

    /** 그룹 행을 잠근 채 바꾸므로 0건은 일어나지 않는다 — 일어나면 데이터가 깨진 것이라 롤백한다. */
    private static void requireUpdated(int updated, long groupId) {
        if (updated == 0) {
            throw new IllegalStateException("잠근 그룹 행을 바꾸지 못했다 groupId=" + groupId);
        }
    }
}
