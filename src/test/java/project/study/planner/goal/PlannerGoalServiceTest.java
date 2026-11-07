package project.study.planner.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.common.exception.BadRequestException;
import project.study.planner.goal.dto.PlannerGoalRequest;
import project.study.planner.goal.dto.PlannerGoalResponse;
import project.study.planner.goal.repository.PlannerGoalRepository;
import project.study.planner.goal.service.PlannerGoalService;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.user.repository.UserRepository;
import project.study.user.service.UserTimeZones;

/** DB 저장은 API 테스트가 검증하고, 여기서는 상속과 05시 경계의 정책을 고정 시계로 검증한다. */
@ExtendWith(MockitoExtension.class)
class PlannerGoalServiceTest {

    private static final Instant BEFORE_FIVE = Instant.parse("2030-03-01T19:59:00Z");
    private static final LocalDate MARCH_SECOND = LocalDate.of(2030, 3, 2);

    @Mock
    private PlannerGoalRepository repository;

    @Mock
    private UserRepository users;

    @Mock
    private StudySubjectLockRepository locks;

    private PlannerGoalService service;

    @BeforeEach
    void setUp() {
        service = at(BEFORE_FIVE);
    }

    @Test
    void 개별_0은_기본값보다_우선한다() {
        when(repository.findDefault(1L)).thenReturn(Optional.of(3600));
        when(repository.findIndividual(1L, MARCH_SECOND)).thenReturn(Optional.of(0));

        assertThat(service.get(1L, MARCH_SECOND)).isEqualTo(new PlannerGoalResponse(0, 0, 3600));
    }

    @Test
    void 개별값이_없을_때만_기본값을_상속하고_둘_다_없으면_null이다() {
        when(repository.findDefault(1L)).thenReturn(Optional.of(0));

        assertThat(service.get(1L, MARCH_SECOND)).isEqualTo(new PlannerGoalResponse(0, null, 0));
        assertThat(service.get(2L, MARCH_SECOND)).isEqualTo(new PlannerGoalResponse(null, null, null));
    }

    @Test
    void 현지_04시59분에는_달력상_오늘이_아직_플래너의_미래다() {
        assertThat(service.save(1L, MARCH_SECOND, new PlannerGoalRequest(1800), null))
                .isEqualTo(new PlannerGoalResponse(1800, 1800, null));
        assertThatThrownBy(() -> service.save(1L, MARCH_SECOND.minusDays(1), new PlannerGoalRequest(1800), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 현지_05시부터_같은_날짜의_설정과_삭제를_거절한다() {
        PlannerGoalService afterBoundary = at(Instant.parse("2030-03-01T20:00:00Z"));

        assertThatThrownBy(() -> afterBoundary.save(1L, MARCH_SECOND, new PlannerGoalRequest(1800), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> afterBoundary.delete(1L, MARCH_SECOND, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 저장된_사용자_시간대를_기준으로_미래를_판정한다() {
        when(users.findTimeZoneById(1L)).thenReturn(Optional.of("Pacific/Auckland"));

        assertThatThrownBy(() -> service.save(1L, MARCH_SECOND, new PlannerGoalRequest(1800), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 읽을_수_없는_요청_시간대는_저장된_시간대로_돌아간다() {
        when(users.findTimeZoneById(1L)).thenReturn(Optional.of("Pacific/Auckland"));

        assertThatThrownBy(() -> service.save(1L, MARCH_SECOND, new PlannerGoalRequest(1800), "Not/AZone"))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1800, 88200})
    void 서비스_직접_호출도_잘못된_초를_거절한다(Integer value) {
        assertThatThrownBy(() -> service.save(1L, MARCH_SECOND, new PlannerGoalRequest(value), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.saveDefault(1L, new PlannerGoalRequest(value), null))
                .isInstanceOf(BadRequestException.class);
    }

    private PlannerGoalService at(Instant instant) {
        return new PlannerGoalService(
                repository, Clock.fixed(instant, ZoneOffset.UTC), new UserTimeZones(users), locks);
    }
}
