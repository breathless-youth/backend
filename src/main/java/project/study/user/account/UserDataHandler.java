package project.study.user.account;

import java.util.Set;

/**
 * 사용자 소유 데이터를 가진 도메인이 구현하는 확장점. 계정 병합(익명 → 기존 소셜 계정)과 탈퇴가 모든 처리기를 부른다.
 * users 행 자체는 건드리지 않는다.
 */
public interface UserDataHandler {

    /** 책임지는 "테이블.컬럼" 목록. 예: "user_dday.user_id" */
    Set<String> columns();

    /** 작을수록 먼저 실행된다 (병합·삭제 공통). */
    int order();

    /**
     * 다른 도메인이 users 행보다 먼저 잡는 행을 잠근다. 병합·탈퇴는 users 행을 {@code FOR UPDATE}로 잡기 전에 이것을 부른다 — 예: 그룹은 그룹 행을
     * 잡은 뒤 users를 가리키는 행을 넣어(FK가 users 행에 {@code FOR KEY SHARE}) 거꾸로 잡으면 교착한다. 그런 행이 없으면 구현하지 않는다.
     * 병합은 옮기는 쪽만 넘긴다(전환은 userId가 그대로라 부르지 않는다).
     */
    default void lockBeforeUsers(long userId) {}

    /** source가 가진 데이터를 target으로 옮긴다. 부딪히면 target이 이긴다. */
    void merge(long sourceUserId, long targetUserId);

    /** userId가 가진 데이터를 전부 지운다. */
    void erase(long userId);
}
