package project.study.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import project.study.mission.service.HomeMissionRewardService;
import project.study.mission.service.HomeMissionService;
import project.study.point.service.PointService;
import project.study.point.service.PointStart;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.repository.PointsShopReminderQueries;
import project.study.push.repository.PushExperimentQueries;

class PointsShopReminderTest {

    private static final long USER = 7;
    private static final Instant NOW = Instant.parse("2026-11-23T11:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private final PointsShopReminderQueries queries = mock(PointsShopReminderQueries.class);
    private final PushExperimentQueries sends = mock(PushExperimentQueries.class);
    private final PushSender push = mock(PushSender.class);
    private final HomeMissionService missions = mock(HomeMissionService.class);
    private final HomeMissionRewardService rewards = mock(HomeMissionRewardService.class);
    private final PointService points = mock(PointService.class);
    private final PointStart launch = mock(PointStart.class);
    private final PointsShopReminder reminder =
            new PointsShopReminder(queries, sends, push, missions, rewards, points, launch);

    @BeforeEach
    void 준비() {
        when(push.isEnabled()).thenReturn(true);
        when(launch.startsOn()).thenReturn(TODAY.minusDays(1));
        when(rewards.stepPoints(true)).thenReturn(List.of(50, 100, 300));
        when(queries.findDue(TODAY)).thenReturn(List.of(USER));
    }

    @ParameterizedTest
    @CsvSource({
        "0,0,not_started,포인트를 모아 캐릭터를 꾸며보세요,지금 미션을 시작하면 소품을 얻을 수 있어요!,/home?from=points_shop_reminder",
        "1,50,one_step,인기 소품 구매까지 미션 하나 남았어요,5분만 공부하면 소품을 살 수 있어요!,/home?from=points_shop_reminder",
        "2,180,two_steps,가지고 있는 180P로 인기 소품을 구매해보세요,첫 소품을 구매하면 300P를 추가로 받아요!,/points/accessories?item=42"
    })
    void 발송_직전의_미션과_잔액에_맞는_문구를_보낸다(int step, int balance, String suffix, String title, String body, String link) {
        String type = "points_shop_reminder_" + suffix;
        when(missions.completedSteps(USER, true)).thenReturn(step);
        when(points.balanceOf(USER)).thenReturn(balance);
        when(queries.affordableAccessory(USER, step == 2 ? balance : 150)).thenReturn(Optional.of(42L));
        when(sends.reserveSend(any(), eq(USER), eq(type), eq(TODAY), eq(NOW))).thenReturn(true);
        when(push.sendToEnabledDevices(eq(List.of(USER)), any())).thenReturn(new PushResult(1, 0, 0));

        reminder.run(NOW);

        var capture = ArgumentCaptor.forClass(PushMessage.class);
        verify(push).sendToEnabledDevices(eq(List.of(USER)), capture.capture());
        PushMessage message = capture.getValue();
        assertThat(message.ko().title()).isEqualTo(title);
        assertThat(message.ko().body()).isEqualTo(body);
        assertThat(message.link()).isEqualTo(link);
        assertThat(message.type()).isEqualTo(type);
        verify(sends).recordAccepted(message.pushId(), 1);
    }

    @Test
    void 첫_소품을_이미_구매했으면_3단계_완료_버튼을_누르기_전에도_제외한다() {
        when(missions.completedSteps(USER, true)).thenReturn(2);
        when(missions.hasPurchasedAccessory(USER)).thenReturn(true);

        reminder.run(NOW);

        verifyNoInteractions(sends);
    }

    @Test
    void 잔액이_부족한_2단계_완료자에게는_구매할_수_있다는_문구를_보내지_않는다() {
        when(missions.completedSteps(USER, true)).thenReturn(2);
        when(points.balanceOf(USER)).thenReturn(50);

        reminder.run(NOW);

        verify(queries).affordableAccessory(USER, 50);
        verifyNoInteractions(sends);
    }

    @Test
    void FCM이_비활성화돼_있을_때는_대상_조회나_발송_예약을_하지_않는다() {
        when(push.isEnabled()).thenReturn(false);

        reminder.run(NOW);

        verifyNoInteractions(queries, sends);
    }

    @Test
    void 하루_발송_슬롯을_이미_다른_작업이_잡았으면_발송하지_않는다() {
        when(queries.affordableAccessory(USER, 150)).thenReturn(Optional.of(42L));

        reminder.run(NOW);

        verify(sends).reserveSend(any(), eq(USER), eq("points_shop_reminder_not_started"), eq(TODAY), eq(NOW));
        verify(push, never()).sendToEnabledDevices(any(), any());
    }
}
