package project.study.group.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 그룹 한도용 사용자 advisory lock (BY-1124). 한 사람이 동시에 그룹 3개를 만들거나 신청 4개를 보내도 개수 검사와 insert 사이로 끼어들지
 * 못하게 직렬화한다.
 *
 * <p>락 순서(바꾸지 않는다): ① {@code study_group} 행 → <b>② 이 락</b> → ③ 룸의 사용자 락({@code RoomRepository.lockUser}) → ④ {@code
 * rooms} 행 → ⑤ {@code room_participations} 행. 앞 단계는 건너뛸 수 있어도 거꾸로 잡지 않는다.
 *
 * <p>키는 {@code 2^41 + userId}다. 룸의 사용자 락(userId 그대로)·초대코드 락(2^40 + hash)과 같은 키 공간에서 부딪히지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GroupLocks {

    private static final long USER_LOCK_OFFSET = 1L << 41;

    private final JdbcClient jdbc;

    /** 트랜잭션이 끝날 때까지 쥔다. 트랜잭션 밖에서 잡으면 바로 풀려 아무것도 막지 못하므로 거절한다. */
    public void lockUser(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("그룹 사용자 락은 트랜잭션 안에서만 잡을 수 있다");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", USER_LOCK_OFFSET + userId)
                .query()
                .listOfRows();
    }
}
