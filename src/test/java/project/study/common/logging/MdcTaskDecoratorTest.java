package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** 제출 스레드의 MDC가 실행 스레드로 복사되고, 실행 후 실행 스레드에는 남지 않는지 본다. */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 제출_시점의_MDC가_실행_스레드에_복사된다() throws InterruptedException {
        MDC.put(LogContext.USER_ID, "42");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.get(LogContext.USER_ID)));

        Thread worker = new Thread(task);
        worker.start();
        worker.join();

        assertThat(seen.get()).isEqualTo("42");
    }

    @Test
    void 실행이_끝나면_실행_스레드의_MDC는_비워진다() throws InterruptedException {
        MDC.put(LogContext.USER_ID, "42");
        AtomicReference<Map<String, String>> after = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> {});

        Thread worker = new Thread(() -> {
            task.run();
            after.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();

        assertThat(after.get()).isNullOrEmpty();
    }

    @Test
    void 제출_시점에_MDC가_비어_있어도_실행된다() throws InterruptedException {
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        Runnable task = decorator.decorate(() -> ran.set(true));

        Thread worker = new Thread(task);
        worker.start();
        worker.join();

        assertThat(ran.get()).isTrue();
    }
}
