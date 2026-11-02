package project.study.group;

/** 그룹 공개 여부 — 만들 때 정하고 바꿀 수 없다. */
public enum GroupVisibility {
    /** 공개 그룹 목록에 노출되고, 신청해서 들어온다 */
    PUBLIC,
    /** 목록에 나오지 않고, 초대코드·링크로만 들어온다 */
    PRIVATE
}
