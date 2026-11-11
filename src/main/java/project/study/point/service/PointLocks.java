package project.study.point.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 포인트 사용자 advisory lock. 한 사람의 적립·구매를 직렬화해 "받아야 할 금액 계산"과 "가졌는지 검사"가 기록 사이로 끼어들지 못하게 한다.
 * 키는 {@code 2^44 + userId}이고 다른 락을 쥔 채 잡지 않는다. 키 공간 목록은 {@code GroupLocks}에 있다.
 */
@Component
@RequiredArgsConstructor
public class PointLocks {

    private static final long USER_LOCK_OFFSET = 1L << 44;

    private final JdbcClient jdbc;

    /** 트랜잭션이 끝날 때까지 쥔다. 트랜잭션 밖에서는 바로 풀려 아무것도 막지 못하므로 거절한다. */
    public void lockUser(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("포인트 사용자 락은 트랜잭션 안에서만 잡을 수 있다");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", USER_LOCK_OFFSET + userId)
                .query()
                .listOfRows();
    }
}
