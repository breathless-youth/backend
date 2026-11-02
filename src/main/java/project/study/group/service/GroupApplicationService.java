package project.study.group.service;

import org.springframework.stereotype.Service;
import project.study.common.exception.NotImplementedException;
import project.study.group.dto.GroupApplicationCreateRequest;
import project.study.group.dto.GroupApplicationCreateResponse;
import project.study.group.dto.GroupApplicationsResponse;
import project.study.group.dto.MyApplicationsResponse;
import project.study.group.dto.ReportRequest;

/** 그룹 신청 REST 계약만 있는 스텁 — 로직은 다음 단계에서 채우고 {@link NotImplementedException}을 지운다 (BY-1124). */
@Service
public class GroupApplicationService {

    public GroupApplicationCreateResponse apply(Long userId, long groupId, GroupApplicationCreateRequest request) {
        throw new NotImplementedException();
    }

    public GroupApplicationsResponse applications(Long userId, long groupId) {
        throw new NotImplementedException();
    }

    public MyApplicationsResponse myApplications(Long userId) {
        throw new NotImplementedException();
    }

    public void cancel(Long userId, long applicationId) {
        throw new NotImplementedException();
    }

    public void accept(Long userId, long applicationId) {
        throw new NotImplementedException();
    }

    public void decline(Long userId, long applicationId) {
        throw new NotImplementedException();
    }

    public void report(Long userId, long applicationId, ReportRequest request) {
        throw new NotImplementedException();
    }
}
