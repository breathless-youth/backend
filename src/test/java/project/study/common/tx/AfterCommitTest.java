package project.study.common.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.TestcontainersConfiguration;

/** 업무 트랜잭션이 롤백되면 푸시·STOMP가 나가지 않아야 한다 (BY-1124) — 실제 트랜잭션 매니저로 커밋·롤백을 가른다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AfterCommitTest {

    @Autowired
    private TransactionTemplate tx;

    @Test
    void 커밋되면_그_뒤에_실행된다() {
        List<String> ran = new ArrayList<>();

        tx.executeWithoutResult(status -> {
            AfterCommit.run(() -> ran.add("push"));
            assertThat(ran).as("커밋 전에는 아직 실행되지 않는다").isEmpty();
        });

        assertThat(ran).containsExactly("push");
    }

    @Test
    void 롤백되면_실행되지_않는다() {
        List<String> ran = new ArrayList<>();

        tx.executeWithoutResult(status -> {
            AfterCommit.run(() -> ran.add("push"));
            status.setRollbackOnly();
        });

        assertThat(ran).isEmpty();
    }

    @Test
    void 예외로_롤백돼도_실행되지_않는다() {
        List<String> ran = new ArrayList<>();

        assertThatCode(() -> tx.executeWithoutResult(status -> {
                    AfterCommit.run(() -> ran.add("push"));
                    throw new IllegalStateException("업무 실패");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ran).isEmpty();
    }

    @Test
    void 트랜잭션_밖이면_바로_실행된다() {
        List<String> ran = new ArrayList<>();

        AfterCommit.run(() -> ran.add("push"));

        assertThat(ran).containsExactly("push");
    }

    @Test
    void 커밋_뒤_작업이_던져도_호출자와_다른_작업에_퍼지지_않는다() {
        List<String> ran = new ArrayList<>();

        assertThatCode(() -> tx.executeWithoutResult(status -> {
                    AfterCommit.run(() -> {
                        throw new IllegalStateException("푸시 실패");
                    });
                    AfterCommit.run(() -> ran.add("두 번째"));
                }))
                .doesNotThrowAnyException();

        assertThat(ran).as("앞 작업이 던져도 뒤 작업은 돈다").containsExactly("두 번째");
    }

    @Test
    void 트랜잭션_밖에서_작업이_던져도_호출자에게_퍼지지_않는다() {
        assertThatCode(() -> AfterCommit.run(() -> {
                    throw new IllegalStateException("푸시 실패");
                }))
                .doesNotThrowAnyException();
    }

    @Test
    void 등록한_순서대로_실행된다() {
        List<Integer> ran = new ArrayList<>();

        tx.executeWithoutResult(status -> {
            AfterCommit.run(() -> ran.add(1));
            AfterCommit.run(() -> ran.add(2));
            AfterCommit.run(() -> ran.add(3));
        });

        assertThat(ran).containsExactly(1, 2, 3);
    }
}
