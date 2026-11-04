package project.study.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.repository.PushExperimentQueries;
import project.study.push.repository.RhythmReminderQueries;
import project.study.push.repository.RhythmReminderQueries.Candidate;
import project.study.push.repository.RhythmReminderQueries.RecentStart;
import project.study.studysession.dto.StudyRhythmResponse;
import project.study.studysession.service.StudySessionService;

@ExtendWith(MockitoExtension.class)
class RhythmReminderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private static final ZoneId KATHMANDU = ZoneId.of("Asia/Kathmandu");
    // 수요일
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Mock
    private RhythmReminderQueries queries;

    @Mock
    private PushExperimentQueries sendLog;

    @Mock
    private StudySessionService studySessionService;

    @Mock
    private PushSender pushSender;

    @InjectMocks
    private RhythmReminder reminder;

    @BeforeEach
    void defaults() {
        lenient().when(pushSender.isEnabled()).thenReturn(true);
        lenient().when(queries.canSend(anyLong(), any())).thenReturn(true);
        lenient().when(queries.lastSendDate(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient()
                .when(sendLog.reserveSend(any(), anyLong(), anyString(), any(), any()))
                .thenReturn(true);
        lenient().when(pushSender.send(any(), any())).thenReturn(new PushResult(1, 0, 0));
    }

    private static Instant at(ZoneId zone, LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant();
    }

    private static Instant seoul(int hour, int minute) {
        return at(SEOUL, TODAY, hour, minute);
    }

    private void candidates(Candidate... candidates) {
        when(queries.findCandidates(anyInt())).thenReturn(List.of(candidates));
    }

    private void recentStarts(RecentStart... starts) {
        when(queries.findRecentStarts(any(), anyInt())).thenReturn(List.of(starts));
    }

    // 평균이 그 시각이 되도록 세션 하나를 둔다
    private static RecentStart startedAt(long userId, ZoneId zone, int hour, int minute) {
        return new RecentStart(userId, at(zone, TODAY.minusDays(1), hour, minute), zone);
    }

    private void tickEvery10Minutes(Instant from, Instant to) {
        for (Instant t = from; !t.isAfter(to); t = t.plusSeconds(600)) {
            reminder.tick(t);
        }
    }

    @Test
    void 피크는_오늘이_평일이면_평일_요일만_합쳐_연속_2시간이_가장_큰_구간의_시작_시각이다() {
        int[][] grid = new int[7][24];
        grid[0][20] = 3600; // 월 20시
        grid[2][21] = 3600; // 수 21시
        grid[5][9] = 99_999; // 토요일은 평일 피크에 넣지 않는다

        assertThat(RhythmReminder.peakStart(grid, DayOfWeek.WEDNESDAY)).isEqualTo(LocalTime.of(20, 0));
        assertThat(RhythmReminder.peakStart(grid, DayOfWeek.SATURDAY)).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void 피크는_4시와_5시를_잇지_않고_합이_같으면_이른_구간을_고른다() {
        int[][] grid = new int[7][24];
        grid[2][4] = 3000;
        grid[2][5] = 3000;
        grid[2][14] = 2000;
        grid[2][15] = 2000;

        // 4시와 5시를 이었다면 6000으로 14시 구간(4000)보다 컸다
        assertThat(RhythmReminder.peakStart(grid, DayOfWeek.WEDNESDAY)).isEqualTo(LocalTime.of(14, 0));

        int[][] tie = new int[7][24];
        tie[2][10] = 1000;
        tie[2][16] = 1000;
        assertThat(RhythmReminder.peakStart(tie, DayOfWeek.WEDNESDAY)).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void 오늘_요일에_기록이_없으면_피크_시각이_없다() {
        int[][] grid = new int[7][24];
        grid[0][20] = 3600;

        assertThat(RhythmReminder.peakStart(grid, DayOfWeek.WEDNESDAY)).isNull();
    }

    @Test
    void 평균_시작_시각은_세션_시간대의_시각을_5시부터_센_분으로_평균해_10분_단위로_맞춘다() {
        // 서울 23시(18시간)와 LA 9시(4시간), 각자 현지 시각으로 읽는다
        Double average = RhythmReminder.averageStartMinute(List.of(startedAt(1, SEOUL, 23, 0), startedAt(1, LA, 9, 0)));
        assertThat(average).isEqualTo((18 + 4) * 60 / 2.0);
        assertThat(RhythmReminder.averageStartMinute(List.of())).isNull();

        assertThat(RhythmReminder.usualStart(null)).isNull();
        assertThat(RhythmReminder.usualStart(0.0)).isEqualTo(LocalTime.of(5, 0));
        assertThat(RhythmReminder.usualStart(14 * 60 + 4.0)).isEqualTo(LocalTime.of(19, 0));
        assertThat(RhythmReminder.usualStart(14 * 60 + 5.0)).isEqualTo(LocalTime.of(19, 10));
        // 다음 날 4시 55분을 넘으면 5시로 돈다
        assertThat(RhythmReminder.usualStart(1439.0)).isEqualTo(LocalTime.of(5, 0));
    }

    @Test
    void 밤_9시부터_아침_8시_전까지는_보내지_않는_시각이다() {
        assertThat(RhythmReminder.isQuiet(LocalTime.of(20, 50))).isFalse();
        assertThat(RhythmReminder.isQuiet(LocalTime.of(21, 0))).isTrue();
        assertThat(RhythmReminder.isQuiet(LocalTime.of(7, 50))).isTrue();
        assertThat(RhythmReminder.isQuiet(LocalTime.of(8, 0))).isFalse();
    }

    @Test
    void FCM_키가_없으면_대상_조회도_하지_않는다() {
        when(pushSender.isEnabled()).thenReturn(false);

        reminder.tick(seoul(20, 0));

        verifyNoInteractions(queries, sendLog, studySessionService);
    }

    @Test
    void 리듬이_열린_사람은_피크_틱에만_홈으로_가는_알림을_받고_자리를_먼저_잡는다() {
        candidates(new Candidate(1L, 20, SEOUL));
        int[][] grid = new int[7][24];
        grid[2][20] = 3600;
        grid[2][21] = 3600;
        when(studySessionService.rhythm(1L)).thenReturn(new StudyRhythmResponse(20, grid));

        reminder.tick(seoul(19, 50));
        reminder.tick(seoul(20, 0).plusSeconds(40));
        reminder.tick(seoul(20, 10));

        ArgumentCaptor<UUID> reserved = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
        var order = inOrder(queries, sendLog, pushSender);
        order.verify(queries).canSend(1L, TODAY);
        order.verify(sendLog).reserveSend(reserved.capture(), eq(1L), eq(RhythmReminder.TYPE_PEAK), eq(TODAY), any());
        order.verify(pushSender).send(eq(List.of(1L)), sent.capture());
        order.verify(sendLog).recordAccepted(reserved.getValue(), 1);
        assertThat(sent.getValue().link()).isEqualTo("/home");
        assertThat(sent.getValue().type()).isEqualTo(RhythmReminder.TYPE_PEAK);
        assertThat(sent.getValue().pushId()).isEqualTo(reserved.getValue());
        verify(pushSender, times(1)).send(any(), any());
        // 피크 알림은 3일 간격을 보지 않고, 미리 계산한 값은 하루 한 번만 만든다
        verify(queries, never()).lastSendDate(anyLong(), any());
        verify(queries, times(1)).findCandidates(anyInt());
    }

    @Test
    void 서울_날짜가_바뀌면_다시_계산한다() {
        when(queries.findCandidates(anyInt())).thenReturn(List.of());

        reminder.tick(seoul(23, 50));
        reminder.tick(seoul(23, 50).plusSeconds(600));

        verify(queries, times(2)).findCandidates(anyInt());
    }

    @Test
    void 해외_사용자는_현지_시각과_현지_날짜로_받는다() {
        candidates(new Candidate(1L, 5, LA));
        recentStarts(startedAt(1L, LA, 19, 0));
        // LA 11월 4일 19시는 서울 11월 5일 12시다
        Instant laSeven = at(LA, TODAY, 19, 0);

        tickEvery10Minutes(laSeven.minusSeconds(1800), laSeven.plusSeconds(1800));

        verify(sendLog).reserveSend(any(), eq(1L), eq(RhythmReminder.TYPE_USUAL), eq(TODAY), eq(laSeven));
        verify(pushSender, times(1)).send(any(), any());
    }

    @Test
    void 분_단위_오프셋_시간대도_한_틱에서만_받는다() {
        candidates(new Candidate(1L, 5, KATHMANDU));
        recentStarts(startedAt(1L, KATHMANDU, 19, 0));
        // 카트만두(+05:45)의 현지 틱은 18:55, 19:05처럼 온다. 서버 틱은 UTC 10분 격자다
        Instant firstTick = at(KATHMANDU, TODAY, 18, 35);

        tickEvery10Minutes(firstTick, firstTick.plusSeconds(3600));

        verify(sendLog).reserveSend(any(), eq(1L), any(), eq(TODAY), eq(at(KATHMANDU, TODAY, 19, 5)));
        verify(pushSender, times(1)).send(any(), any());
    }

    @Test
    void 리듬이_안_열린_사람은_마지막_평균_알림_뒤_3일이_지나야_다시_받는다() {
        candidates(new Candidate(1L, 5, SEOUL), new Candidate(2L, 5, SEOUL), new Candidate(3L, 5, SEOUL));
        recentStarts(startedAt(1L, SEOUL, 19, 0), startedAt(2L, SEOUL, 19, 0), startedAt(3L, SEOUL, 19, 0));
        when(queries.lastSendDate(1L, RhythmReminder.TYPE_USUAL)).thenReturn(Optional.of(TODAY.minusDays(3)));
        when(queries.lastSendDate(2L, RhythmReminder.TYPE_USUAL)).thenReturn(Optional.of(TODAY.minusDays(2)));

        reminder.tick(seoul(19, 0));

        verify(sendLog).reserveSend(any(), eq(1L), eq(RhythmReminder.TYPE_USUAL), eq(TODAY), any());
        verify(sendLog, never()).reserveSend(any(), eq(2L), any(), any(), any());
        verify(sendLog).reserveSend(any(), eq(3L), eq(RhythmReminder.TYPE_USUAL), eq(TODAY), any());
        verifyNoInteractions(studySessionService);
    }

    @Test
    void 최근_2주에_세션이_없으면_보내지_않는다() {
        candidates(new Candidate(1L, 5, SEOUL));
        recentStarts();

        tickEvery10Minutes(seoul(8, 0), seoul(20, 50));

        verify(queries, never()).canSend(anyLong(), any());
    }

    @Test
    void 오늘_공부했거나_공부_중이면_건너뛴다() {
        candidates(new Candidate(1L, 5, SEOUL));
        recentStarts(startedAt(1L, SEOUL, 19, 0));
        when(queries.canSend(1L, TODAY)).thenReturn(false);

        reminder.tick(seoul(19, 0));

        verifyNoInteractions(sendLog);
    }

    @Test
    void 보낼_시각이_밤_9시_이후면_그날은_보내지_않는다() {
        candidates(new Candidate(1L, 5, SEOUL));
        recentStarts(startedAt(1L, SEOUL, 21, 0));

        tickEvery10Minutes(seoul(20, 0), seoul(23, 50));

        verify(queries, never()).canSend(anyLong(), any());
    }

    @Test
    void 다른_서버_푸시로_오늘_자리가_이미_찼으면_보내지_않는다() {
        candidates(new Candidate(1L, 5, SEOUL));
        recentStarts(startedAt(1L, SEOUL, 19, 0));
        when(sendLog.reserveSend(any(), eq(1L), any(), any(), any())).thenReturn(false);

        reminder.tick(seoul(19, 0));

        verify(pushSender, never()).send(any(), any());
    }

    @Test
    void 한_사람의_리듬_계산이_실패해도_나머지는_보낸다() {
        candidates(new Candidate(1L, 20, SEOUL), new Candidate(2L, 5, SEOUL));
        recentStarts(startedAt(2L, SEOUL, 19, 0));
        when(studySessionService.rhythm(1L)).thenThrow(new IllegalStateException("boom"));

        reminder.tick(seoul(19, 0));

        verify(pushSender).send(eq(List.of(2L)), any());
    }
}
