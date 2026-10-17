package project.study.studysession.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 같은 사용자의 수동 기록 추가·수정을 트랜잭션 동안 직렬화하는 advisory 락 (ADR-0031).
 * 키 공간은 룸 유저 락(userId 그대로)·초대코드 락(2^40 ± 2^31)·과목 락(9,724,000,000 + userId)과 겹치지 않게 2^41을 더한다.
 */
@Repository
@RequiredArgsConstructor
public class ManualStudySessionLockRepository {

    private static final long USER_LOCK_OFFSET = 1L << 41;

    private final JdbcClient jdbc;

    /** 트랜잭션 끝에 자동 해제된다 — 반드시 @Transactional 안에서 부른다. */
    public void lockUser(Long userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:offset + :userId)")
                .param("offset", USER_LOCK_OFFSET)
                .param("userId", userId)
                .query()
                .listOfRows();
    }
}
