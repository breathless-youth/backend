package project.study.group;

/** 공개 그룹의 입장 방식. 비공개 그룹은 항상 신청 후 입장 값으로 저장하고 초대코드로만 참여한다. */
public enum GroupJoinPolicy {
    APPROVAL,
    OPEN
}
