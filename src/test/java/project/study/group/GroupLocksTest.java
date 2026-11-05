package project.study.group;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;
import project.study.group.service.GroupLocks;
import project.study.group.support.GroupProbe;
import project.study.room.repository.RoomRepository;
import project.study.studysession.repository.ManualStudySessionLockRepository;
import project.study.subject.repository.StudySubjectLockRepository;

/**
 * 그룹 사용자 락의 키 공간 (BY-1124). 같은 사용자의 다른 도메인 advisory 락(룸 사용자·수동 기록·과목)과 서로를 기다리면 안 된다 — 한 사람이
 * 수동 기록을 저장하는 동안 그룹을 못 만드는 식의 보이지 않는 대기가 생긴다. 다른 연결(REQUIRES_NEW)에서 짧은 {@code lock_timeout}으로 잡아
 * 보고, 기다리게 되면 실패한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupLocksTest {

    @Autowired
    private GroupLocks locks;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private ManualStudySessionLockRepository manualLocks;

    @Autowired
    private StudySubjectLockRepository subjectLocks;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate tx;

    private TransactionTemplate otherConnection;
    private long user;

    @BeforeEach
    void setUp() {
        otherConnection = new TransactionTemplate(tx.getTransactionManager());
        otherConnection.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        user = new GroupProbe(jdbc).insertUser();
    }

    /** 다른 연결에서 이 작업이 락을 기다리면 {@code lock_timeout}으로 예외가 난다. */
    private void inOtherConnection(Runnable action) {
        otherConnection.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '1500ms'").update();
            action.run();
        });
    }

    @Test
    void 사용자_락은_트랜잭션_안에서만_잡을_수_있다() {
        assertThatThrownBy(() -> locks.lockUser(user)).isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> tx.executeWithoutResult(status -> locks.lockUser(user)))
                .doesNotThrowAnyException();
    }

    @Test
    void 같은_사용자의_그룹_락끼리는_서로를_기다린다() {
        // 대조군: 이 방법으로 대기를 실제로 잡아낼 수 있어야 아래 "기다리지 않는다" 테스트가 의미 있다
        tx.executeWithoutResult(status -> {
            locks.lockUser(user);

            assertThatThrownBy(() -> inOtherConnection(() -> locks.lockUser(user)))
                    .hasMessageContaining("lock timeout");
        });
    }

    @Test
    void 다른_사용자의_그룹_락은_기다리지_않는다() {
        long otherUser = new GroupProbe(jdbc).insertUser();

        tx.executeWithoutResult(status -> {
            locks.lockUser(user);

            assertThatCode(() -> inOtherConnection(() -> locks.lockUser(otherUser)))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void 그룹_락을_쥔_사용자가_다른_도메인_락을_잡아도_기다리지_않는다() {
        tx.executeWithoutResult(status -> {
            locks.lockUser(user);

            assertThatCode(() -> inOtherConnection(() -> manualLocks.lockUser(user)))
                    .as("수동 기록 락(2^41 + userId)")
                    .doesNotThrowAnyException();
            assertThatCode(() -> inOtherConnection(() -> roomRepository.lockUser(user)))
                    .as("룸 사용자 락(userId)")
                    .doesNotThrowAnyException();
            assertThatCode(() -> inOtherConnection(() -> subjectLocks.lockUser(user)))
                    .as("과목 락(9,724,000,000 + userId)")
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void 같은_이름_락끼리는_서로를_기다리고_공백과_대소문자는_같은_이름이다() {
        tx.executeWithoutResult(status -> {
            locks.lockPublicName("Study Room");

            assertThatThrownBy(() -> inOtherConnection(() -> locks.lockPublicName(" study  room ")))
                    .hasMessageContaining("lock timeout");
        });
    }

    @Test
    void 다른_이름_락과_사용자_락은_기다리지_않는다() {
        tx.executeWithoutResult(status -> {
            locks.lockPublicName("Study Room");

            assertThatCode(() -> inOtherConnection(() -> locks.lockPublicName("다른 이름")))
                    .doesNotThrowAnyException();
            assertThatCode(() -> inOtherConnection(() -> locks.lockUser(user))).doesNotThrowAnyException();
            assertThatCode(() -> inOtherConnection(() -> roomRepository.lockUser(user)))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void 이름_락도_트랜잭션_안에서만_잡을_수_있다() {
        assertThatThrownBy(() -> locks.lockPublicName("Study Room")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 다른_도메인_락을_쥔_사용자가_그룹_락을_잡아도_기다리지_않는다() {
        tx.executeWithoutResult(status -> {
            manualLocks.lockUser(user);
            roomRepository.lockUser(user);
            subjectLocks.lockUser(user);

            assertThatCode(() -> inOtherConnection(() -> locks.lockUser(user))).doesNotThrowAnyException();
        });
    }
}
