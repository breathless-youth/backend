package project.study.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.repository.PushExperimentQueries;

@ExtendWith(MockitoExtension.class)
class FirstSessionPushExperimentTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Duration DELAY = Duration.ofHours(2);

    @Mock
    private PushExperimentQueries queries;

    @Mock
    private PushSender pushSender;

    private FirstSessionPushExperiment experiment(Duration delay) {
        return new FirstSessionPushExperiment(queries, pushSender, delay);
    }

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(TODAY, java.time.LocalTime.of(hour, minute), KST)
                .toInstant();
    }

    @Test
    void 밤_10시부터_아침_8시_전까지는_아무것도_하지_않는다() {
        experiment(DELAY).tick(at(22, 0));
        experiment(DELAY).tick(at(7, 50));

        verifyNoInteractions(queries, pushSender);
    }

    @Test
    void D0는_가입하고_N시간이_지난_한_틱_안의_사람을_찾는다() {
        experiment(DELAY).tick(at(15, 0).plusMillis(30));

        verify(queries).findFirstSessionCandidates(at(12, 50), at(13, 0));
    }

    @Test
    void 틱이_늦게_돌아도_10분_격자로_맞춰_창이_빠지거나_겹치지_않는다() {
        experiment(DELAY).tick(at(15, 1).plusSeconds(20));
        verify(queries).findFirstSessionCandidates(at(12, 50), at(13, 0));

        experiment(DELAY).tick(at(20, 1));
        verify(queries)
                .findFirstSessionCandidates(
                        TODAY.minusDays(1).atStartOfDay(KST).toInstant(),
                        TODAY.atStartOfDay(KST).toInstant());
    }

    @Test
    void D0_창은_오늘_0시_전으로_넘어가지_않는다() {
        experiment(Duration.ofHours(8)).tick(at(8, 0));
        verify(queries, never()).findFirstSessionCandidates(any(), any());

        experiment(Duration.ofHours(8)).tick(at(8, 10));
        verify(queries).findFirstSessionCandidates(at(0, 0), at(0, 10));
    }

    @Test
    void D1은_20시_첫_틱에만_어제_가입자를_찾는다() {
        Instant yesterday = TODAY.minusDays(1).atStartOfDay(KST).toInstant();
        Instant today = TODAY.atStartOfDay(KST).toInstant();

        experiment(DELAY).tick(at(20, 0));
        experiment(DELAY).tick(at(20, 10));

        verify(queries).findFirstSessionCandidates(yesterday, today);
    }

    @Test
    void push_집단만_홈으로_가는_알림을_push_id와_함께_보내고_접수_수를_남긴다() {
        when(queries.findFirstSessionCandidates(at(12, 50), at(13, 0))).thenReturn(List.of(1L, 2L));
        when(queries.assign(eq(FirstSessionPushExperiment.EXPERIMENT), anyLong(), anyString(), eq(at(15, 0))))
                .thenReturn("PUSH", "CONTROL");
        when(queries.reserveSend(any(), eq(1L), eq("first_session_d0"), eq(TODAY), eq(at(15, 0))))
                .thenReturn(true);
        when(pushSender.send(eq(List.of(1L)), any())).thenReturn(new PushResult(2, 0, 0));

        experiment(DELAY).tick(at(15, 0));

        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        var order = inOrder(queries, pushSender);
        order.verify(queries).reserveSend(reserved.capture(), eq(1L), any(), any(), any());
        order.verify(pushSender).send(eq(List.of(1L)), sent.capture());
        order.verify(queries).recordAccepted(reserved.getValue(), 2);
        assertThat(sent.getValue().pushId()).isEqualTo(reserved.getValue());
        assertThat(sent.getValue().link()).isEqualTo("/");
        assertThat(sent.getValue().type()).isEqualTo("first_session_d0");
        verify(pushSender, never()).send(eq(List.of(2L)), any());
    }

    @Test
    void 처음_배정할_집단은_무작위로_두_집단에_나뉜다() {
        List<Long> users =
                java.util.stream.LongStream.rangeClosed(1, 200).boxed().toList();
        when(queries.findFirstSessionCandidates(any(), any())).thenReturn(users);
        when(queries.assign(any(), anyLong(), anyString(), any())).thenReturn("CONTROL");

        experiment(DELAY).tick(at(15, 0));

        ArgumentCaptor<String> arms = ArgumentCaptor.forClass(String.class);
        verify(queries, org.mockito.Mockito.times(200)).assign(any(), anyLong(), arms.capture(), any());
        assertThat(arms.getAllValues()).contains("PUSH", "CONTROL");
    }

    @Test
    void 그날_자리를_잡지_못하면_보내지_않는다() {
        when(queries.findFirstSessionCandidates(any(), any())).thenReturn(List.of(1L));
        when(queries.assign(any(), anyLong(), anyString(), any())).thenReturn("PUSH");
        when(queries.reserveSend(any(), anyLong(), any(), any(), any())).thenReturn(false);

        experiment(DELAY).tick(at(15, 0));

        verifyNoInteractions(pushSender);
    }

    @Test
    void 한_사람의_실패가_나머지를_막지_않는다() {
        when(queries.findFirstSessionCandidates(any(), any())).thenReturn(List.of(1L, 2L));
        when(queries.assign(any(), eq(1L), anyString(), any())).thenThrow(new IllegalStateException("boom"));
        when(queries.assign(any(), eq(2L), anyString(), any())).thenReturn("PUSH");
        when(queries.reserveSend(any(), eq(2L), any(), any(), any())).thenReturn(true);
        when(pushSender.send(any(), any())).thenReturn(PushResult.NONE);

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
