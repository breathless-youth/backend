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
 * <p>advisory lock 키 공간은 모든 도메인이 DB 하나에서 나눠 쓴다. 지금 쓰는 키(코드에서 {@code pg_advisory}를 찾아 확인):
 *
 * <ul>
 *   <li>룸 사용자 락({@code RoomRepository.lockUser}): {@code userId} 그대로
 *   <li>룸 초대코드 락({@code RoomRepository.lockInviteCode}): {@code 2^40 + hashtext(code)} — {@code hashtext}가 32비트 정수라
 *       {@code 2^40 ± 2^31}
 *   <li>수동 기록 락({@code ManualStudySessionLockRepository}): {@code 2^41 + userId}
 *   <li>과목 락({@code StudySubjectLockRepository}): {@code 9,724,000,000 + userId}
 *   <li>이 락: {@code 2^42 + userId}
 * </ul>
 *
 * <p>2^41은 수동 기록 락이 이미 쓰고 있어 같은 사용자의 수동 기록 저장과 그룹 만들기가 서로를 기다리게 되므로 2^42를 쓴다. 사용자 ID는
 * {@code BIGSERIAL}이라 2^40(약 1.1조)에 한참 못 미친다. 그러면 위 다른 키는 모두 {@code 2^41 + 2^40 = 3 × 2^40} 아래에 있고, 이 락은
 * {@code 2^42 = 4 × 2^40} 이상이라 어느 것과도 겹치지 않는다. 새 advisory 락을 만들면 이 목록에 적고 2^43 이상에서 고른다.
 */
@Component
@RequiredArgsConstructor
public class GroupLocks {

    private static final long USER_LOCK_OFFSET = 1L << 42;

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
