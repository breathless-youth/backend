package project.study.group.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.study.common.exception.NotImplementedException;
import project.study.group.dto.GroupInviteResponse;
import project.study.group.dto.GroupJoinRequest;
import project.study.group.dto.GroupJoinResponse;
import project.study.group.dto.GroupMembersResponse;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.group.dto.ReportRequest;

/**
 * 그룹 멤버·초대·공부방 자리·멤버 신고 (BY-1124). 공부방 자리 잡기·나가기는 {@link GroupRoomService}에 맡긴다. 나머지는 아직 REST 계약만 있는
 * 스텁 — 로직은 다음 단계에서 채우고 {@link NotImplementedException}을 지운다.
 */
@Service
@RequiredArgsConstructor
public class GroupMemberService {

    private final GroupRoomService groupRooms;

    public GroupMembersResponse members(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public void transferOwner(Long userId, long groupId, long targetUserId) {
        throw new NotImplementedException();
    }

    public void kick(Long userId, long groupId, long targetUserId) {
        throw new NotImplementedException();
    }

    public void leave(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public GroupInviteResponse invite(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public GroupInviteResponse regenerateInvite(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public GroupJoinResponse join(Long userId, GroupJoinRequest request) {
        throw new NotImplementedException();
    }

    public GroupRoomJoinResponse joinRoom(Long userId, long groupId) {
        return groupRooms.join(userId, groupId);
    }

    public void leaveRoom(Long userId, long groupId) {
        groupRooms.leave(userId, groupId);
    }

    public void reportMember(Long userId, long groupId, long targetUserId, ReportRequest request) {
        throw new NotImplementedException();
    }
}
