package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionServiceTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00)
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    @Mock
    private StudySessionRepository studySessionRepository;

    // createSessions는 순수 로직이라 리포지토리를 사용하지 않는다 — 저장 경로는 API 통합테스트가 검증
    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, CLOCK);
    }

    private StatusEvent event(EventStatus status, String startedAt, String endedAt) {
        return new StatusEvent(status, Instant.parse(startedAt), Instant.parse(endedAt));
    }

    @Test
    void 총공부시간과_순공시간은_요청값을_그대로_저장한다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:40:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 6600, 5000, events).get(0);

        assertThat(session.getStudySec()).isEqualTo(6600);
        // 이벤트 구간과 무관하게 요청의 focusSec가 저장된다
        assertThat(session.getFocusSec()).isEqualTo(5000);
        assertThat(session.getUserId()).isEqualTo(1L);
        assertThat(session.getEvents()).hasSize(2);
    }

    @Test
    void 순공_시간이_음수면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, END, 7200, -1, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 순공_시간이_총공부시간을_초과하면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, END, 7000, 7001, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 순공_시간은_0과_총공부시간_경계값을_허용한다() {
        assertThat(service.createSessions(1L, START, END, 7200, 0, List.of())
                        .get(0)
                        .getFocusSec())
                .isEqualTo(0);
        assertThat(service.createSessions(1L, START, END, 7200, 7200, List.of())
                        .get(0)
                        .getFocusSec())
                .isEqualTo(7200);
    }

    @Test
    void 총공부시간이_음수면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, END, -1, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 총공부시간이_방_체류시간을_초과하면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, END, 7201, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void PAUSE를_제외한_시간까지는_총공부시간으로_허용된다() {
        // PAUSE 10분 → 방 체류시간(7200) - PAUSE(600) = 6600초까지 허용
        List<StatusEvent> events = List.of(event(EventStatus.PAUSE, "2026-07-24T08:00:00Z", "2026-07-24T08:10:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 6600, 6600, events).get(0);

        assertThat(session.getStudySec()).isEqualTo(6600);
    }

    @Test
    void PAUSE를_제외한_시간을_초과하는_총공부시간은_거부된다() {
        List<StatusEvent> events = List.of(event(EventStatus.PAUSE, "2026-07-24T08:00:00Z", "2026-07-24T08:10:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 6601, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void PAUSE가_아닌_이벤트는_총공부시간_상한에_영향을_주지_않는다() {
        // PHONE은 순공시간 타이머만 멈춘다 — 총공부시간 상한은 방 체류시간 그대로
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:00:00Z", "2026-07-24T08:10:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 7200, 0, events).get(0);

        assertThat(session.getStudySec()).isEqualTo(7200);
    }

    @Test
    void 통계_날짜는_시작_시각의_한국_날짜를_따른다() {
        // 2026-07-23T16:30:00Z = KST 2026-07-24 01:30
        Instant start = Instant.parse("2026-07-23T16:30:00Z");
        Instant end = Instant.parse("2026-07-23T18:30:00Z");

        StudySession session =
                service.createSessions(1L, start, end, 3600, 3600, List.of()).get(0);

        assertThat(session.getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 순서가_뒤섞인_이벤트는_시작_시각_기준으로_정렬된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.AWAY, "2026-07-24T09:00:00Z", "2026-07-24T09:10:00Z"),
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 7000, 6300, events).get(0);

        assertThat(session.getEvents().get(0).getStatus()).isEqualTo(EventStatus.DEVICE);
        assertThat(session.getEvents().get(1).getStatus()).isEqualTo(EventStatus.AWAY);
    }

    @Test
    void 이벤트가_맞닿아_이어지는_것은_허용된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:10:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 7200, 6600, events).get(0);

        assertThat(session.getEvents()).hasSize(2);
    }

    @Test
    void 세션의_집중률을_계산한다() {
        StudySession session =
                service.createSessions(1L, START, END, 6600, 6000, List.of()).get(0);

        // 6000 / 6600 × 100 = 90.909... → 90.9
        assertThat(StudySessionService.focusRate(session.getFocusSec(), session.getStudySec()))
                .isEqualTo(90.9);
    }

    @Test
    void 집중률은_소수_한_자리로_반올림된다() {
        assertThat(StudySessionService.focusRate(1, 3)).isEqualTo(33.3);
        assertThat(StudySessionService.focusRate(2, 3)).isEqualTo(66.7);
        assertThat(StudySessionService.focusRate(7200, 7200)).isEqualTo(100.0);
        assertThat(StudySessionService.focusRate(0, 7200)).isEqualTo(0.0);
    }

    @Test
    void 총공부시간이_0이면_집중률은_0이다() {
        assertThat(StudySessionService.focusRate(0, 0)).isEqualTo(0.0);
    }

    @Test
    void 종료가_시작보다_빠르거나_같으면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, START, 0, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 십분_미만_세션은_거부한다() {
        // 9분 59초 — 10분 미만 세션은 저장하지 않는다 (저장이 안 되므로 스트릭에도 잡히지 않는다)
        assertThatThrownBy(() -> service.createSessions(1L, START, START.plusSeconds(599), 0, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 정확히_십분_세션은_허용된다() {
        StudySession session = service.createSessions(1L, START, START.plusSeconds(600), 600, 600, List.of())
                .get(0);

        assertThat(session.getStudySec()).isEqualTo(600);
    }

    @Test
    void 이십사_시간을_초과하는_세션은_거부한다() {
        Instant start = NOW.minusSeconds(60 * 60 * 25);
        Instant end = start.plusSeconds(60 * 60 * 24 + 1);

        assertThatThrownBy(() -> service.createSessions(1L, start, end, 0, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 종료_시각이_허용_오차를_넘는_미래이면_거부한다() {
        Instant end = NOW.plusSeconds(60 * 6); // now + 6분 (허용 오차 5분 초과)

        assertThatThrownBy(() -> service.createSessions(1L, START, end, 0, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 이벤트의_종료가_시작보다_빠르거나_같으면_거부한다() {
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 0, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 세션_구간을_벗어난_이벤트는_거부한다() {
        List<StatusEvent> events = List.of(event(EventStatus.AWAY, "2026-07-24T09:50:00Z", "2026-07-24T10:10:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 0, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 겹치는_이벤트는_거부한다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:10:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:15:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 0, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void date로_조회하면_그_날짜_하루_기간으로_조회한다() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of());
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of());

        service.list(1L, date);

        verify(studySessionRepository).findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date);
    }

    @Test
    void 목록_응답에_세션_개수와_합계가_담긴다() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        StudySession first = new StudySession(1L, date, START, END, 7200, 6600, List.of());
        StudySession second = new StudySession(1L, date, START, END, 3600, 3000, List.of());
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of(first, second));
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of());

        StudySessionListResponse response = service.list(1L, date);

        assertThat(response.sessionCount()).isEqualTo(2);
        assertThat(response.totalStudySec()).isEqualTo(10800);
        assertThat(response.totalFocusSec()).isEqualTo(9600);
    }

    @Test
    void 목록_응답에_그_달_공부한_날짜_목록이_담긴다() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        List<LocalDate> studiedDates = List.of(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 24));
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of());
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(studiedDates);

        StudySessionListResponse response = service.list(1L, date);

        assertThat(response.studiedDatesInMonth()).isEqualTo(studiedDates);
    }

    @Test
    void 세션_요약에_상태별_이벤트_건수가_담긴다() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        StudySession session = new StudySession(
                1L,
                date,
                START,
                END,
                7200,
                6600,
                List.of(
                        event(EventStatus.PHONE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                        event(EventStatus.PHONE, "2026-07-24T08:10:00Z", "2026-07-24T08:15:00Z"),
                        event(EventStatus.PAUSE, "2026-07-24T08:20:00Z", "2026-07-24T08:25:00Z")));
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of(session));
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of());

        StudySessionListResponse response = service.list(1L, date);

        Map<EventStatus, Long> eventCounts = response.sessions().get(0).eventCounts();
        assertThat(eventCounts.get(EventStatus.PHONE)).isEqualTo(2L);
        assertThat(eventCounts.get(EventStatus.PAUSE)).isEqualTo(1L);
        assertThat(eventCounts.get(EventStatus.DEVICE)).isEqualTo(0L);
        assertThat(eventCounts.get(EventStatus.AWAY)).isEqualTo(0L);
    }

    @Test
    void 목록_응답의_totalEventCounts는_세션별_건수의_합이다() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        StudySession first = new StudySession(
                1L,
                date,
                START,
                END,
                7200,
                6600,
                List.of(event(EventStatus.PHONE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z")));
        StudySession second = new StudySession(
                1L,
                date,
                START,
                END,
                3600,
                3000,
                List.of(
                        event(EventStatus.PHONE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                        event(EventStatus.AWAY, "2026-07-24T08:10:00Z", "2026-07-24T08:15:00Z")));
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of(first, second));
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of());

        StudySessionListResponse response = service.list(1L, date);

        assertThat(response.totalEventCounts().get(EventStatus.PHONE)).isEqualTo(2L);
        assertThat(response.totalEventCounts().get(EventStatus.AWAY)).isEqualTo(1L);
        assertThat(response.totalEventCounts().get(EventStatus.DEVICE)).isEqualTo(0L);
    }

    @Test
    void date가_달의_마지막날이어도_그달_전체_범위로_조회한다() {
        LocalDate date = LocalDate.of(2026, 2, 28);
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of());
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                .thenReturn(List.of());

        service.list(1L, date);

        verify(studySessionRepository)
                .findDistinctStatDatesBetween(1L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
    }
}
