package project.study.group.service;

import org.springframework.stereotype.Service;
import project.study.common.exception.NotImplementedException;
import project.study.group.dto.GroupInviteResponse;
import project.study.group.dto.GroupJoinRequest;
import project.study.group.dto.GroupJoinResponse;
import project.study.group.dto.GroupMembersResponse;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.group.dto.ReportRequest;

/** 그룹 멤버·초대·공부방 자리·멤버 신고 REST 계약만 있는 스텁 — 로직은 다음 단계에서 채우고 {@link NotImplementedException}을 지운다 (BY-1124). */
@Service
public class GroupMemberService {

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
        throw new NotImplementedException();
    }

    public void leaveRoom(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public void reportMember(Long userId, long groupId, long targetUserId, ReportRequest request) {
        throw new NotImplementedException();
    }
}
