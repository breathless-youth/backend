package project.study.subject.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 같은 사용자의 과목 생성·순서 저장을 트랜잭션 동안 직렬화하는 advisory 락 (ADR-0022). 락 없이는 동시 생성 두 건이
 * 같은 살아있는 목록을 읽어 같은 색·같은 순서를 받는다. 룸 도메인의 lockUser와 키 공간을 나누려고 오프셋을 더한다.
 */
@Repository
@RequiredArgsConstructor
public class StudySubjectLockRepository {

    private static final long USER_LOCK_OFFSET = 9_724_000_000L;

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
