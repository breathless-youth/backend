package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.report.fact.ExperimentFact;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackNotificationException;
import project.study.metrics.slack.SlackNotifier;
import project.study.push.service.FirstSessionPushExperiment;

@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    // 2026-10-13T01:00Z = KST 10:00 → 기준일(어제) 10/12
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate A = LocalDate.of(2026, 10, 12);

    @Mock
    private ReportDataLoader loader;

    @Mock
    private FirstSessionPushExperiment firstSessionPushExperiment;

    @Mock
    private SlackNotifier slackNotifier;

    private DailyReportService service() {
        return new DailyReportService(loader, firstSessionPushExperiment, slackNotifier, CLOCK, 0);
    }

    private static SlackMessage titled(String prefix) {
        return argThat(message -> message != null && message.title().startsWith(prefix));
    }

    @Test
    void 발송기가_꺼져_있으면_계산도_하지_않는다() {
        when(slackNotifier.isEnabled()).thenReturn(false);

        service().send();

        verifyNoInteractions(loader);
        verify(slackNotifier, never()).post(any());
    }

    @Test
    void 어제_기준으로_요약을_보내고_그_ts에_스레드_일곱_개를_단다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(loader.load(eq(A), any())).thenReturn(ReportDataFixture.data(A, true));
        when(slackNotifier.post(titled("📊 10/12(월) 일일 지표"))).thenReturn("ts-1");

        service().send();

        verify(slackNotifier, times(7)).reply(eq("ts-1"), any());
        verify(slackNotifier).reply(eq("ts-1"), titled("🕐 가입"));
        verify(slackNotifier).reply(eq("ts-1"), titled("🧑‍🎓 어제 공부한"));
    }

    @Test
    void 스레드_하나가_실패해도_나머지를_보낸다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(loader.load(eq(A), any())).thenReturn(ReportDataFixture.data(A, true));
        when(slackNotifier.post(any())).thenReturn("ts-1");
        doThrow(new SlackNotificationException("Slack API 응답 실패 (HTTP 500)"))
                .when(slackNotifier)
                .reply(eq("ts-1"), titled("🕐 가입"));
        doNothing()
                .when(slackNotifier)
                .reply(eq("ts-1"), argThat(message -> !message.title().startsWith("🕐 가입")));

        service().send();

        verify(slackNotifier, times(7)).reply(eq("ts-1"), any());
    }

    @Test
    void 실험_사실을_읽지_못해도_리포트는_나가고_실험_스레드만_계산_실패다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(firstSessionPushExperiment.findFacts()).thenThrow(new IllegalStateException("db down"));
        when(loader.load(eq(A), isNull())).thenReturn(ReportDataFixture.data(A, true, (List<ExperimentFact>) null));
        when(slackNotifier.post(any())).thenReturn("ts-1");

        service().send();

        verify(slackNotifier, times(7)).reply(eq("ts-1"), any());
        verify(slackNotifier)
                .reply(
                        eq("ts-1"),
                        argThat(message -> message.title().startsWith("🔔")
                                && message.text().contains("계산 실패")));
    }

    @Test
    void 본_메시지가_실패하면_스레드를_달지_않는다() {
        when(slackNotifier.isEnabled()).thenReturn(true);
        when(loader.load(eq(A), any())).thenReturn(ReportDataFixture.data(A, true));
        when(slackNotifier.post(any())).thenThrow(new SlackNotificationException("Slack API 호출 실패"));

        assertThatThrownBy(() -> service().send()).isInstanceOf(SlackNotificationException.class);
        verify(slackNotifier, never()).reply(anyString(), any());
    }
}
