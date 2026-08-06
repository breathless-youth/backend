package project.study.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentry.spring.boot4.SentryAutoConfiguration;
import io.sentry.spring7.SentryExceptionResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;

/**
 * prod의 {@code sentry.exception-resolver-order}가 실제로 "Sentry가 먼저 캡처한다"를 만들어내는지 검증한다.
 *
 * <p>이 값이 조용히 깨지기 쉬워서 테스트로 못 박아둔다. Sentry의 SentryExceptionResolver는 기본 order가 1인데,
 * Spring MVC가 등록하는 예외 리졸버 컴포짓(@ExceptionHandler를 실행하는 ExceptionHandlerExceptionResolver 포함)은
 * order 0이다. {@link GlobalExceptionHandler}가 {@code @ExceptionHandler(Exception.class)}로 모든 예외를
 * 소비하므로, DispatcherServlet은 컴포짓에서 이미 결과를 받아 체인을 멈춘다 — 즉 order를 0보다 낮추지 않으면
 * Sentry 리졸버는 호출조차 되지 않고 대시보드가 텅 비게 된다 (ADR-0011).
 *
 * <p>검증 방식은 DispatcherServlet.initHandlerExceptionResolvers와 동일하다: 컨텍스트의 모든
 * HandlerExceptionResolver 빈을 모아 AnnotationAwareOrderComparator로 정렬한 뒤 첫 자리를 확인한다.
 * 순서 값만 비교하는 것이 아니라 "실제로 등록된 빈들 사이에서 Sentry가 앞에 오는지"를 본다.
 * 이벤트가 Sentry 서버에 실제로 도달하는지는 여전히 검증 범위가 아니다 (DSN 없이 돌린다).
 */
class SentryExceptionResolverOrderTest {

    private static final String PROD_CONFIG = "application-prod.yaml";
    private static final String ORDER_PROPERTY = "sentry.exception-resolver-order";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class))
            .withUserConfiguration(WebMvcResolverConfiguration.class)
            // DSN이 비면 SDK는 no-op으로 동작한다. 자동 설정과 리졸버 빈 등록에는 값의 존재만 필요하다
            .withPropertyValues("sentry.dsn=");

    @Test
    void prod_설정에_예외_리졸버_순서가_명시돼_있다() {
        assertThat(prodExceptionResolverOrder())
                .as("%s에서 %s가 사라지면 기본값 1로 돌아가 Sentry가 아무 예외도 못 받는다", PROD_CONFIG, ORDER_PROPERTY)
                .isNotNull();
    }

    @Test
    void prod_설정을_적용하면_Sentry_리졸버가_ExceptionHandler_컴포짓보다_먼저_실행된다() {
        contextRunner
                .withPropertyValues(ORDER_PROPERTY + "=" + prodExceptionResolverOrder())
                .run(context -> {
                    assertThat(context).hasSingleBean(SentryExceptionResolver.class);
                    assertThat(context).hasSingleBean(HandlerExceptionResolverComposite.class);

                    List<HandlerExceptionResolver> chain = resolverChain(context.getSourceApplicationContext());

                    assertThat(chain)
                            .as("DispatcherServlet이 호출하는 순서 — 맨 앞이 Sentry여야 한다")
                            .first()
                            .isInstanceOf(SentryExceptionResolver.class);
                    assertThat(context.getBean(SentryExceptionResolver.class).getOrder())
                            .as("Spring MVC 예외 리졸버 컴포짓(order 0)보다 앞서야 한다")
                            .isLessThan(context.getBean(HandlerExceptionResolverComposite.class)
                                    .getOrder());
                });
    }

    @Test
    void 설정이_없으면_Sentry_리졸버가_컴포짓_뒤로_밀린다() {
        // 이번 회귀의 재현. 기본값(1)일 때 실제로 뒤로 밀린다는 것을 확인해두지 않으면
        // 위 테스트가 "설정과 무관하게 항상 통과하는 테스트"인지 구분할 수 없다
        contextRunner.run(context -> {
            List<HandlerExceptionResolver> chain = resolverChain(context.getSourceApplicationContext());

            assertThat(chain).first().isInstanceOf(HandlerExceptionResolverComposite.class);
            assertThat(context.getBean(SentryExceptionResolver.class).getOrder())
                    .isEqualTo(1);
        });
    }

    /** DispatcherServlet.initHandlerExceptionResolvers와 같은 방식으로 리졸버 체인을 구성한다. */
    private static List<HandlerExceptionResolver> resolverChain(ApplicationContext context) {
        List<HandlerExceptionResolver> resolvers = new ArrayList<>(
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerExceptionResolver.class, true, false)
                        .values());
        AnnotationAwareOrderComparator.sort(resolvers);
        return resolvers;
    }

    /** 테스트가 prod의 실제 값을 그대로 쓰도록 yaml에서 직접 읽는다 (값을 테스트에 복붙해두면 회귀를 못 잡는다). */
    private static Object prodExceptionResolverOrder() {
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load(PROD_CONFIG, new ClassPathResource(PROD_CONFIG));
            return sources.stream()
                    .map(source -> source.getProperty(ORDER_PROPERTY))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException(PROD_CONFIG + "을 읽을 수 없다", e);
        }
    }

    /** @EnableWebMvc가 실제 운영과 동일하게 order 0짜리 HandlerExceptionResolverComposite를 등록한다. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebMvcResolverConfiguration {}
}
