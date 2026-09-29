package project.study.common.sentry;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.protocol.Mechanism;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.NotFoundException;

class BotScanEventFilterTest {

    private final BotScanEventFilter filter = new BotScanEventFilter();

    @ParameterizedTest
    @ValueSource(strings = {"livewire/update", "wp-content/plugins/x/eval-stdin.php", "/.env", "apiary"})
    void api_밖_경로의_404는_버린다(String path) {
        assertThat(send(noResource(path))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api/nope", "/api/nope", "api", "/api"})
    void api_아래_없는_경로의_404는_앱_버그일_수_있어_보낸다(String path) {
        assertThat(send(noResource(path))).isNotNull();
    }

    @Test
    void 리졸버가_감싼_봇_404도_버린다() {
        // SentryExceptionResolver는 원본 예외를 ExceptionMechanismException으로 감싸 이벤트를 만든다
        Throwable wrapped =
                new ExceptionMechanismException(new Mechanism(), noResource("livewire/update"), Thread.currentThread());

        assertThat(send(wrapped)).isNull();
    }

    @Test
    void 도메인_예외는_그대로_보낸다() {
        assertThat(send(new NotFoundException("복구할 세션이 없습니다"))).isNotNull();
        assertThat(send(new ConflictException("이미 사용 중인 닉네임입니다"))).isNotNull();
        assertThat(send(new IllegalStateException("boom"))).isNotNull();
    }

    @Test
    void 예외가_없는_이벤트는_그대로_보낸다() {
        SentryEvent event = new SentryEvent();

        assertThat(filter.execute(event, new Hint())).isSameAs(event);
    }

    private SentryEvent send(Throwable throwable) {
        return filter.execute(new SentryEvent(throwable), new Hint());
    }

    private static NoResourceFoundException noResource(String path) {
        return new NoResourceFoundException(HttpMethod.GET, "/" + path, path);
    }
}
