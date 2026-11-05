package project.study.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.push.dto.FirstSessionCandidate;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.dto.PushText;
import project.study.push.repository.PushExperimentQueries;

@ExtendWith(MockitoExtension.class)
class FirstSessionPushExperimentTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Duration DELAY = Duration.ofHours(2);

    @Mock
    private PushExperimentQueries queries;

    @Mock
    private PushSender pushSender;

    @BeforeEach
    void fcmEnabled() {
        lenient().when(pushSender.isEnabled()).thenReturn(true);
        lenient().when(queries.assign(any(), anyLong(), anyString(), any())).thenReturn("PUSH");
        lenient()
                .when(queries.reserveSend(any(), anyLong(), any(), any(), any()))
                .thenReturn(true);
        lenient().when(pushSender.send(any(), any())).thenReturn(PushResult.NONE);
    }

    private FirstSessionPushExperiment experiment(Duration delay) {
        return new FirstSessionPushExperiment(queries, pushSender, delay);
    }

    private static Instant at(ZoneId zone, LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant();
    }

    private static Instant at(int hour, int minute) {
        return at(KST, TODAY, hour, minute);
    }

    private static FirstSessionCandidate seoul(long userId, Instant createdAt) {
        return new FirstSessionCandidate(userId, createdAt, KST);
    }

    private void candidates(FirstSessionCandidate... candidates) {
        when(queries.findFirstSessionCandidates(any(), any())).thenReturn(List.of(candidates));
    }

    /** 이 틱에 보낸 알림 종류 — 보내지 않았으면 빈 목록. */
    private List<String> sentTypes() {
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        verify(queries, org.mockito.Mockito.atLeast(0)).reserveSend(any(), anyLong(), types.capture(), any(), any());
        return types.getAllValues();
    }

    @Test
    void 후보는_틱마다_현지_어제_가입자까지_덮는_이틀_창에서_찾는다() {
        experiment(DELAY).tick(at(15, 0).plusMillis(30));

        verify(queries).findFirstSessionCandidates(at(15, 0).minus(Duration.ofDays(2)), at(15, 0));
    }

    @Test
    void 틱이_늦게_돌아도_10분_격자로_맞춘다() {
        candidates(seoul(1, at(12, 50)));

        experiment(DELAY).tick(at(15, 1).plusSeconds(20));

        verify(queries).findFirstSessionCandidates(at(15, 0).minus(Duration.ofDays(2)), at(15, 0));
        assertThat(sentTypes()).containsExactly("first_session_d0");
    }

    @Test
    void D0는_가입하고_N시간이_지난_한_틱_안의_사람에게만_보낸다() {
        candidates(seoul(1, at(12, 50)), seoul(2, at(12, 49)), seoul(3, at(13, 0)));

        experiment(DELAY).tick(at(15, 0));

        verify(queries).assign(any(), eq(1L), anyString(), any());
        verify(queries, never()).assign(any(), eq(2L), anyString(), any());
        verify(queries, never()).assign(any(), eq(3L), anyString(), any());
    }

    @Test
    void D0는_현지_오늘_가입한_사람만이다() {
        // 8시간 지연이면 08:00 틱의 대상은 어제 23:50~24:00 가입자라 보내지 않는다
        candidates(seoul(1, at(KST, TODAY.minusDays(1), 23, 55)), seoul(2, at(0, 5)));

        experiment(Duration.ofHours(8)).tick(at(8, 0));
        experiment(Duration.ofHours(8)).tick(at(8, 10));

        verify(queries, never()).assign(any(), eq(1L), anyString(), any());
        verify(queries).assign(any(), eq(2L), anyString(), any());
    }

    @Test
    void 현지_밤_10시부터_아침_8시_전까지는_보내지_않는다() {
        // 둘 다 그 틱이 D0 시각이다
        candidates(seoul(1, at(19, 55)), seoul(2, at(5, 45)));

        experiment(DELAY).tick(at(22, 0));
        experiment(DELAY).tick(at(7, 50));

        verify(queries, never()).assign(any(), anyLong(), anyString(), any());
        verify(pushSender, never()).send(any(), any());
    }

    @Test
    void 뉴욕_사용자의_야간_시간은_뉴욕_시각으로_판단한다() {
        // 뉴욕 07:00 = 서울 21:00 — 서울로는 보낼 시각이지만 뉴욕은 아직 아침 8시 전이다
        Instant nyMorning = at(NEW_YORK, TODAY, 7, 0);
        // 뉴욕 10:00 = 서울 자정 — 서울로는 야간 시간이지만 뉴욕은 보낼 시각이다
        Instant nyLateMorning = at(NEW_YORK, TODAY, 10, 0);
        // 가입 + N이 틱 직전 10분 안에 드는 사람들
        candidates(
                new FirstSessionCandidate(1, nyMorning.minus(DELAY).minusSeconds(60), NEW_YORK),
                new FirstSessionCandidate(2, nyLateMorning.minus(DELAY).minusSeconds(60), NEW_YORK));

        experiment(DELAY).tick(nyMorning);
        experiment(DELAY).tick(nyLateMorning);

        verify(queries, never()).assign(any(), eq(1L), anyString(), any());
        verify(queries).reserveSend(any(), eq(2L), eq("first_session_d0"), eq(TODAY), eq(nyLateMorning));
    }

    @Test
    void D1은_뉴욕_20시_틱에_뉴욕_어제_가입자에게_뉴욕_날짜로_보낸다() {
        LocalDate nyToday = LocalDate.of(2026, 11, 2);
        FirstSessionCandidate yesterday =
                new FirstSessionCandidate(1, at(NEW_YORK, nyToday.minusDays(1), 23, 30), NEW_YORK);
        FirstSessionCandidate today = new FirstSessionCandidate(2, at(NEW_YORK, nyToday, 1, 0), NEW_YORK);
        candidates(yesterday, today);
        Instant eightPm = at(NEW_YORK, nyToday, 20, 0);

        experiment(DELAY).tick(eightPm);
        experiment(DELAY).tick(eightPm.plus(Duration.ofMinutes(10)));

        verify(queries).reserveSend(any(), eq(1L), eq("first_session_d1"), eq(nyToday), eq(eightPm));
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(eq(List.of(1L)), sent.capture());
        assertThat(sent.getValue().en())
                .isEqualTo(new PushText(
                        "How about just 5 minutes? 👀",
                        "Getting started is the hardest part. Try the timer before today ends."));
        verify(queries, never()).assign(any(), eq(2L), anyString(), any());
        verify(queries, org.mockito.Mockito.times(1)).assign(any(), eq(1L), anyString(), any());
    }

    @Test
    void 정각이_아닌_시간대도_20시_뒤_첫_틱에_D1을_받는다() {
        ZoneId kathmandu = ZoneId.of("Asia/Kathmandu");
        LocalDate localToday = LocalDate.of(2026, 11, 2);
        candidates(new FirstSessionCandidate(1, at(kathmandu, localToday.minusDays(1), 9, 0), kathmandu));

        // 카트만두 20:00은 14:15Z라 10분 격자의 14:20Z 틱(20:05)이 첫 틱이다
        experiment(DELAY).tick(Instant.parse("2026-11-02T14:10:00Z"));
        experiment(DELAY).tick(Instant.parse("2026-11-02T14:20:00Z"));
        experiment(DELAY).tick(Instant.parse("2026-11-02T14:30:00Z"));

        verify(queries, org.mockito.Mockito.times(1))
                .reserveSend(any(), eq(1L), eq("first_session_d1"), eq(localToday), any());
    }

    @Test
    void FCM_키가_없으면_대상_조회도_배정도_하지_않는다() {
        when(pushSender.isEnabled()).thenReturn(false);

        FirstSessionPushExperiment experiment = experiment(DELAY);
        experiment.tick(at(15, 0));
        experiment.tick(at(20, 0));

        verifyNoInteractions(queries);
        verify(pushSender, never()).send(any(), any());
    }

    @Test
    void push_집단만_홈으로_가는_알림을_push_id와_함께_보내고_접수_수를_남긴다() {
        candidates(seoul(1, at(12, 50)), seoul(2, at(12, 55)));
        when(queries.assign(eq(FirstSessionPushExperiment.EXPERIMENT), anyLong(), anyString(), eq(at(15, 0))))
                .thenReturn("PUSH", "CONTROL");
        when(pushSender.send(eq(List.of(1L)), any())).thenReturn(new PushResult(2, 0, 0));

        experiment(DELAY).tick(at(15, 0));

        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        var order = inOrder(queries, pushSender);
        order.verify(queries).reserveSend(reserved.capture(), eq(1L), eq("first_session_d0"), eq(TODAY), eq(at(15, 0)));
        order.verify(pushSender).send(eq(List.of(1L)), sent.capture());
        order.verify(queries).recordAccepted(reserved.getValue(), 2);
        assertThat(sent.getValue().pushId()).isEqualTo(reserved.getValue());
        assertThat(sent.getValue().link()).isEqualTo("/");
        assertThat(sent.getValue().type()).isEqualTo("first_session_d0");
        assertThat(sent.getValue().en())
                .isEqualTo(new PushText(
                        "Build a 5-minute focus habit 🔥", "Just turn on the timer for 5 minutes and start small."));
        verify(pushSender, never()).send(eq(List.of(2L)), any());
    }

    @Test
    void 처음_배정할_집단은_무작위로_두_집단에_나뉜다() {
        candidates(LongStream.rangeClosed(1, 200)
                .mapToObj(id -> seoul(id, at(12, 55)))
                .toArray(FirstSessionCandidate[]::new));
        when(queries.assign(any(), anyLong(), anyString(), any())).thenReturn("CONTROL");

        experiment(DELAY).tick(at(15, 0));

        ArgumentCaptor<String> arms = ArgumentCaptor.forClass(String.class);
        verify(queries, org.mockito.Mockito.times(200)).assign(any(), anyLong(), arms.capture(), any());
        assertThat(arms.getAllValues()).contains("PUSH", "CONTROL");
    }

    @Test
    void 그날_자리를_잡지_못하면_보내지_않는다() {
        candidates(seoul(1, at(12, 55)));
        when(queries.reserveSend(any(), anyLong(), any(), any(), any())).thenReturn(false);

        experiment(DELAY).tick(at(15, 0));

        verify(pushSender, never()).send(any(), any());
    }

    @Test
    void 한_사람의_실패가_나머지를_막지_않는다() {
        candidates(seoul(1, at(12, 55)), seoul(2, at(12, 55)));
        when(queries.assign(any(), eq(1L), anyString(), any())).thenThrow(new IllegalStateException("boom"));

        experiment(DELAY).tick(at(15, 0));

        verify(pushSender).send(eq(List.of(2L)), any());
    }

    @Test
    void 리포트용_사실을_읽기_전에_첫_세션을_채운다() {
        experiment(DELAY).findFacts();

        var order = inOrder(queries);
        order.verify(queries).fillFirstSessions(FirstSessionPushExperiment.EXPERIMENT);
        order.verify(queries).fillDaySeven(FirstSessionPushExperiment.EXPERIMENT);
        order.verify(queries).findFacts(FirstSessionPushExperiment.EXPERIMENT);
    }
}
