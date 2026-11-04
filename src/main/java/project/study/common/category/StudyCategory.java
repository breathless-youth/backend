package project.study.common.category;

/**
 * 시험·목표 분류 — 프로필(`users.category`)과 그룹 공부방이 같이 쓴다. 선언 순서가 화면·리포트의 표시 순서다.
 *
 * <p>프로필 API의 허용값(`ProfileUpdateRequest`의 `@Pattern`)은 애노테이션 상수라 이 enum에서 만들 수 없어 문자열로 따로 있고,
 * `StudyCategoryTest`가 둘이 어긋나지 않는지 잡는다.
 */
public enum StudyCategory {
    CSAT("수능"),
    SCHOOL_EXAM("내신"),
    UNIVERSITY_EXAM("대학 시험"),
    CIVIL_SERVICE("공무원"),
    CERTIFICATE("자격증"),
    LANGUAGE("어학"),
    JOB("취업"),
    PROFESSIONAL("전문직"),
    ETC("기타");

    private final String label;

    StudyCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
