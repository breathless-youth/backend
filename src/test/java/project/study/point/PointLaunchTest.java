package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.study.TestcontainersConfiguration;
import project.study.point.service.DailyRewardService;
import project.study.point.service.PointLaunchService;
import project.study.point.service.PointStart;
import project.study.point.service.StudyPointService;
import project.study.user.service.AppStoreVersionSource;

/** 포인트는 포인트 기능이 든 앱이 App Store에 출시된 날부터 쌓인다 (BY-1314). 스토어 조회는 가짜로 바꾼다. */
@SpringBootTest(properties = "app.points.launch.version=26.47.0")
@Import(TestcontainersConfiguration.class)
class PointLaunchTest {

    @Autowired
    PointLaunchService launch;

    @Autowired
    PointStart start;

    @Autowired
    StudyPointService studyPoints;

    @Autowired
    DailyRewardService earn;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    AppStoreVersionSource store;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM point_launch").update();
    }

    @Test
    void 출시_전에는_포인트가_쌓이지_않고_출시를_보면_그날부터_쌓인다() {
        long member = fixture.member();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        given(store.releasedVersion()).willReturn(Optional.of("26.46.0"));

        assertThat(launch.sync()).isFalse();
        assertThat(start.counts(member, today)).isFalse();
        assertThat(start.started(member)).isFalse();

        given(store.releasedVersion()).willReturn(Optional.of("26.47.0"));
        assertThat(launch.sync()).isTrue();
        assertThat(launch.sync()).isFalse();

        assertThat(start.startsOn()).isEqualTo(today);
        assertThat(start.started(member)).isTrue();
        assertThat(start.counts(member, today)).isTrue();
        assertThat(start.counts(member, today.minusDays(1))).isFalse();
    }

    @Test
    void 심사_계정은_출시_전에도_포인트를_받는다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        given(store.releasedVersion()).willReturn(Optional.of("26.46.0"));
        launch.sync();
        long reviewer = fixture.reviewer();

        assertThat(start.counts(reviewer, today)).isTrue();
        assertThat(start.started(reviewer)).isTrue();
    }

    @Test
    void 출시_전에도_받기_화면의_오늘_공부_시간은_실제_값이다() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        long member = fixture.member();
        fixture.measuredSession(member, today.toString(), 1, 900);

        assertThat(earn.overview(member).study().recognizedFocusSec()).isEqualTo(900);
        assertThat(earn.overview(member).study().earnedPoints()).isZero();
    }

    @Test
    void 형식이_틀린_버전이나_조회_실패로는_시작하지_않는다() {
        given(store.releasedVersion()).willReturn(Optional.of("99.0.0-beta"));
        assertThat(launch.sync()).isFalse();
        given(store.releasedVersion()).willThrow(new IllegalStateException("timeout"));
        assertThat(launch.sync()).isFalse();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM point_launch")
                        .query(Integer.class)
                        .single())
                .isZero();
    }
}
