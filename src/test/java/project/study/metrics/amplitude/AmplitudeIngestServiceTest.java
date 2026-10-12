package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.metrics.amplitude.AmplitudeIngestService.IngestResult;
import project.study.metrics.amplitude.repository.AmplitudeIngestRepository;

@ExtendWith(MockitoExtension.class)
class AmplitudeIngestServiceTest {

    // 2026-10-13 09:00 KST
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_11 = LocalDate.of(2026, 10, 11);
    private static final LocalDate OCT_12 = LocalDate.of(2026, 10, 12);

    @Mock
    private AmplitudeExportSource source;

    @Mock
    private AmplitudeWindowWriter writer;

    @Mock
    private AmplitudeIngestRepository repository;

    private AmplitudeIngestService service(Instant now, int backfillDays) {
        return new AmplitudeIngestService(source, writer, repository, Clock.fixed(now, ZoneOffset.UTC), backfillDays);
    }

    @Test
    void 백필_기간의_처리_안_된_창을_오래된_것부터_적재한다() {
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(OCT_10, OCT_12)).thenReturn(Set.of(OCT_11));
        when(source.fetch(any())).thenReturn(WindowAggregate.empty());
        when(writer.write(any(), any())).thenReturn(true);

        IngestResult result = service(NOW, 3).ingestPending();

        InOrder order = inOrder(source);
        order.verify(source).fetch(new ExportWindow(OCT_10));
        order.verify(source).fetch(new ExportWindow(OCT_12));
        verify(source, never()).fetch(new ExportWindow(OCT_11));
        assertThat(result).isEqualTo(new IngestResult(2, 0));
    }

    @Test
    void 아직_준비_안_된_어제_창은_건너뛴다() {
        // 2026-10-13 02:00 KST — 어제 창은 03:00 KST부터 준비된다
        Instant beforeReady = Instant.parse("2026-10-12T17:00:00Z");
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(OCT_10, OCT_12)).thenReturn(Set.of(OCT_10, OCT_11));

        IngestResult result = service(beforeReady, 3).ingestPending();

        verify(source, never()).fetch(any());
        assertThat(result).isEqualTo(new IngestResult(0, 0));
    }

    @Test
    void 창_하나가_실패해도_다음_창을_적재하고_실패를_센다() {
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(OCT_10, OCT_12)).thenReturn(Set.of());
        when(source.fetch(new ExportWindow(OCT_10))).thenReturn(WindowAggregate.empty());
        when(source.fetch(new ExportWindow(OCT_11))).thenThrow(new AmplitudeExportException("HTTP 500"));
        when(source.fetch(new ExportWindow(OCT_12))).thenReturn(WindowAggregate.empty());
        when(writer.write(any(), any())).thenReturn(true);

        IngestResult result = service(NOW, 3).ingestPending();

        assertThat(result).isEqualTo(new IngestResult(2, 1));
        verify(writer, never()).write(eq(new ExportWindow(OCT_11)), any());
    }

    @Test
    void 연속_세_번_실패하면_남은_창을_건너뛴다() {
        when(source.isEnabled()).thenReturn(true);
        when(repository.findProcessedDates(LocalDate.of(2026, 10, 8), OCT_12)).thenReturn(Set.of());
        when(source.fetch(any())).thenThrow(new AmplitudeExportException("timeout"));

        IngestResult result = service(NOW, 5).ingestPending();

        assertThat(result).isEqualTo(new IngestResult(0, 3));
        verify(source, never()).fetch(new ExportWindow(OCT_11));
    }

    @Test
    void 키가_없으면_아무것도_하지_않는다() {
        when(source.isEnabled()).thenReturn(false);

        IngestResult result = service(NOW, 3).ingestPending();

        assertThat(result).isEqualTo(new IngestResult(0, 0));
        verifyNoInteractions(repository, writer);
    }
}
