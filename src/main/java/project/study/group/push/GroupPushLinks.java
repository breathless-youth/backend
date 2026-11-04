package project.study.group.push;

import project.study.common.category.StudyCategory;

/**
 * 그룹 푸시를 눌렀을 때 앱이 여는 경로 (BY-1124). 스킴 없는 경로로 쓴다 — dev 백엔드 하나가 두 빌드에 보내서 앱 스킴을 박으면 한쪽에서 안
 * 열린다. 푸시 link 문자열은 여기서만 만든다.
 */
public final class GroupPushLinks {

    private GroupPushLinks() {}

    /** 그룹 홈 */
    public static String group(long groupId) {
        return "/groups/" + groupId;
    }

    /** 그룹장의 신청 목록 */
    public static String applications(long groupId) {
        return group(groupId) + "/applications";
    }

    /** 공개 그룹 찾기 탭 */
    public static String publicTab() {
        return "/groups?tab=public";
    }

    /** 공개 그룹 찾기 탭, 시험·목표 하나로 거른 채 */
    public static String publicTab(StudyCategory category) {
        return publicTab() + "&category=" + category.name();
    }
}
