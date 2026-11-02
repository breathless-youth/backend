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

    /** source가 가진 데이터를 target으로 옮긴다. 부딪히면 target이 이긴다. */
    void merge(long sourceUserId, long targetUserId);

    /** userId가 가진 데이터를 전부 지운다. */
    void erase(long userId);
}
