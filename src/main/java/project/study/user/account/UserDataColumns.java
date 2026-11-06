package project.study.user.account;

import java.util.Set;

/** 처리기 없이 두는 사용자 참조 컬럼. 빠뜨림 방지 테스트(UserDataCoverageTest)가 이 목록과 처리기 목록의 합을 DB와 맞춰 본다. */
public final class UserDataColumns {

    /** 병합·탈퇴에서 건드리지 않기로 한 컬럼 (사용자 결정). 지금은 없다. */
    public static final Set<String> IGNORED = Set.of();

    private UserDataColumns() {}
}
