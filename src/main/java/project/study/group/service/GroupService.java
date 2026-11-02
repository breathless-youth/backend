package project.study.group.service;

import org.springframework.stereotype.Service;
import project.study.common.category.StudyCategory;
import project.study.common.exception.NotImplementedException;
import project.study.group.dto.GroupCreateRequest;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupUpdateRequest;
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;

/** 그룹 REST 계약만 있는 스텁 — 로직은 다음 단계에서 채우고 {@link NotImplementedException}을 지운다 (BY-1124). */
@Service
public class GroupService {

    /** 그룹을 만들고 ID를 돌려준다. 만든 사람은 멤버이자 그룹장이 된다. */
    public long create(Long userId, GroupCreateRequest request) {
        throw new NotImplementedException();
    }

    public MyGroupsResponse myGroups(Long userId) {
        throw new NotImplementedException();
    }

    public GroupHomeResponse home(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public void update(Long userId, long groupId, GroupUpdateRequest request) {
        throw new NotImplementedException();
    }

    public PublicGroupPageResponse publicGroups(Long userId, String query, StudyCategory category, int page, int size) {
        throw new NotImplementedException();
    }

    public PublicGroupDetailResponse publicGroup(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public void setNotification(Long userId, long groupId, boolean enabled) {
        throw new NotImplementedException();
    }
}
