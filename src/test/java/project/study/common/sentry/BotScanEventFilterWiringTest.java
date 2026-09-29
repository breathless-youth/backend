package project.study.common.sentry;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.spring.boot4.SentryAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.core.ResolvableType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 필터가 실제 Sentry 설정에 걸리는지, 그리고 필터가 가정하는 리소스 경로 형식이 실제 요청에서
 * 그대로 나오는지 검증한다. 단위 테스트만으로는 둘 다 못 잡는다 — 빈 등록이 빠지거나 Spring이
 * 경로를 다른 형태로 넘기면 필터가 조용히 아무것도 안 거른다.
 */
class BotScanEventFilterWiringTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withBean(BotScanEventFilter.class)
            // DSN이 비면 SDK는 no-op으로 동작하지만 옵션 구성은 그대로 일어난다
            .withPropertyValues("sentry.dsn=");

    @Test
    void Sentry_자동_설정이_필터를_beforeSend로_등록한다() {
        contextRunner.run(context -> {
            // DSN이 비면 SDK가 no-op 옵션을 쓰므로, 자동 설정이 만든 옵션 구성을 새 옵션에 직접 적용해 본다
            SentryOptions options = new SentryOptions();
            context.<Sentry.OptionsConfiguration<SentryOptions>>getBeanProvider(
                            ResolvableType.forClassWithGenerics(Sentry.OptionsConfiguration.class, SentryOptions.class))
                    .orderedStream()
                    .forEach(config -> config.configure(options));

            assertThat(options.getBeforeSend()).isSameAs(context.getBean(BotScanEventFilter.class));
        });
    }

    @Test
    void 실제_404의_리소스_경로가_필터_판정대로_갈린다() {
        contextRunner.run(context -> {
            MockMvcTester mvc = MockMvcTester.from(context);

            assertThat(resourcePath(mvc.get().uri("/livewire/update").exchange()))
                    .isEqualTo("livewire/update");
            assertThat(resourcePath(mvc.get().uri("/api/nope").exchange())).isEqualTo("api/nope");
            // 끝 슬래시는 떼어진다 — 필터가 "api"를 /api/ 아래로 봐야 하는 이유
            assertThat(resourcePath(mvc.get().uri("/api/").exchange())).isEqualTo("api");
        });
    }

    private static String resourcePath(MvcTestResult result) {
        assertThat(result.getMvcResult().getResolvedException()).isInstanceOf(NoResourceFoundException.class);
        return ((NoResourceFoundException) result.getMvcResult().getResolvedException()).getResourcePath();
    }
}
