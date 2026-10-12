package project.study.metrics.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetricsJobRunnerTest {

    private final MetricsJobRunner runner = new MetricsJobRunner();

    @AfterEach
    void stop() {
        runner.shutdown();
    }

    @Test
    void 제출한_작업을_순서대로_한_스레드에서_돌린다() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();

        runner.submit("a", () -> ran.add("a:" + Thread.currentThread().getName()));
        runner.submit("b", () -> ran.add("b:" + Thread.currentThread().getName()))
                .get(5, TimeUnit.SECONDS);

        assertThat(ran).containsExactly("a:metrics-job", "b:metrics-job");
    }

    @Test
    void 작업이_실패해도_예외가_밖으로_나가지_않고_다음_작업은_돈다() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();

        assertThatCode(() -> runner.submit("boom", () -> {
                            throw new IllegalStateException("boom");
                        })
                        .get(5, TimeUnit.SECONDS))
                .doesNotThrowAnyException();
        runner.submit("after", () -> ran.add("after")).get(5, TimeUnit.SECONDS);

        assertThat(ran).containsExactly("after");
    }
}
