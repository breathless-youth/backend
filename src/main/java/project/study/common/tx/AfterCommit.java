package project.study.common.tx;

import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 업무 트랜잭션이 커밋된 뒤에만 부수 효과(푸시·STOMP 발송)를 실행한다 (BY-1124). 롤백되면 아무것도 나가지 않아, 사용자가 받은 알림과
 * DB 상태가 어긋나지 않는다.
 *
 * <p>트랜잭션 동기화가 꺼져 있으면(트랜잭션 밖 호출) 바로 실행한다. 실행 중 예외는 잡아서 로그와 Sentry에만 남기고 던지지 않는다 — 커밋은
 * 이미 끝나 호출자가 되돌릴 수 없고, 한 작업의 실패가 뒤에 등록된 작업을 막으면 안 된다.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("커밋 뒤 작업 실패", e);
            Sentry.captureException(e);
        }
    }
}
