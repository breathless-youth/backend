package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.swagger.v3.oas.models.OpenAPI;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import project.study.metrics.scheduler.DailyReportScheduler;
import project.study.metrics.service.DailyReportService;
import project.study.studysession.repository.StudySessionRepository;
import project.study.studysession.service.StudySessionService;
import project.study.user.service.UserService;

/**
 * 프로필 이름이 아니라 프로퍼티 스위치가 기능을 켜는지 확인한다 (BY-640). 값이 없으면 꺼짐이 기본이라,
 * 프로필이 잘못 들어와도({@code prod,dev}, 기본 yaml 추가 등) 시딩·문서 안내가 조용히 켜지지 않는다.
 */
class FeatureSwitchTest {

    private final ApplicationContextRunner seeder = new ApplicationContextRunner()
            .withBean(UserService.class, () -> mock(UserService.class))
            .withBean(StudySessionService.class, () -> mock(StudySessionService.class))
            .withBean(StudySessionRepository.class, () -> mock(StudySessionRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(DevDataSeeder.class);

    private final ApplicationContextRunner report = new ApplicationContextRunner()
            .withBean(DailyReportService.class, () -> mock(DailyReportService.class))
            .withUserConfiguration(DailyReportScheduler.class);

    private final ApplicationContextRunner openApi =
            new ApplicationContextRunner().withUserConfiguration(OpenApiConfig.class);

    @Test
    void 시더는_스위치가_없으면_뜨지_않는다() {
        seeder.run(context -> assertThat(context).doesNotHaveBean(DevDataSeeder.class));
    }

    @Test
    void 시더는_프로필이_dev여도_스위치가_없으면_뜨지_않는다() {
        seeder.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).doesNotHaveBean(DevDataSeeder.class));
    }

    @Test
    void 시더는_스위치를_켜면_뜬다() {
        seeder.withPropertyValues("app.seed.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevDataSeeder.class));
    }

    @Test
    void 일일_리포트_스케줄러는_스위치가_없으면_뜨지_않는다() {
        report.run(context -> assertThat(context).doesNotHaveBean(DailyReportScheduler.class));
    }

    @Test
    void 일일_리포트_스케줄러는_스위치를_켜면_뜬다() {
        report.withPropertyValues("app.report.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DailyReportScheduler.class));
    }

    // Spring의 boolean 변환은 "on"/"1"도 true지만 @ConditionalOnProperty는 "true"만 인정한다 — 두 곳이
    // 같은 값을 다르게 읽으면 시딩 없이 안내만 붙으므로, 시더가 안 뜨는 값에는 안내도 붙지 않아야 한다
    @Test
    void 시더가_인정하지_않는_값에는_API_문서_안내도_붙지_않는다() {
        seeder.withPropertyValues("app.seed.enabled=on")
                .run(context -> assertThat(context).doesNotHaveBean(DevDataSeeder.class));
        openApi.withPropertyValues("app.seed.enabled=on")
                .run(context -> assertThat(
                                context.getBean(OpenAPI.class).getInfo().getDescription())
                        .doesNotContain("목데이터 안내"));
        openApi.withPropertyValues("app.seed.enabled=TRUE")
                .run(context -> assertThat(
                                context.getBean(OpenAPI.class).getInfo().getDescription())
                        .contains("목데이터 안내"));
    }

    @Test
    void API_문서의_목데이터_안내는_시딩_스위치와_함께_켜진다() {
        openApi.run(
                context -> assertThat(context.getBean(OpenAPI.class).getInfo().getDescription())
                        .doesNotContain("목데이터 안내"));
        openApi.withPropertyValues("app.seed.enabled=true")
                .run(context -> assertThat(
                                context.getBean(OpenAPI.class).getInfo().getDescription())
                        .contains("목데이터 안내")
                        .contains(DevDataSeeder.DEMO_DEVICE_ID));
    }
}
