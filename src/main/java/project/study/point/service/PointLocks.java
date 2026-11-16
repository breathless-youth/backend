package project.study.point.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 포인트 사용자 advisory lock. 한 사람의 적립·구매를 직렬화해 "받아야 할 금액 계산"과 "가졌는지 검사"가 기록 사이로 끼어들지 못하게 한다.
 * 키는 {@code 2^44 + userId}이고 다른 락을 쥔 채 잡지 않는다 — 결제 락({@link #lockPurchase})만 예외로, 늘 결제 락 → 사용자 락
 * 순서다. 키 공간 목록은 {@code GroupLocks}에 있다.
 */
@Component
@RequiredArgsConstructor
public class PointLocks {

    private static final long USER_LOCK_OFFSET = 1L << 44;
    private static final long PURCHASE_LOCK_OFFSET = 1L << 45;

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

    /**
     * 스토어 결제 한 건의 락. 키는 {@code 2^45 + hashtext(스토어:거래 ID)}다. 같은 결제의 지급 알림과 환불 알림을 한 줄로 세워, 환불이
     * 지급 사이로 끼어들어 사라지지 않게 한다. 사용자 락보다 먼저 잡는다.
     */
    public void lockPurchase(String store, String transactionId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 락은 트랜잭션 안에서만 잡을 수 있다");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(:offset + hashtext(:key))")
                .param("offset", PURCHASE_LOCK_OFFSET)
                .param("key", store + ":" + transactionId)
                .query()
                .listOfRows();
    }
}
