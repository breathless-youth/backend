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
    void 총시간은_서버가_계산하고_순공_시간은_요청값을_그대로_저장한다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:40:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 5000, events).get(0);

        assertThat(session.getSessionSec()).isEqualTo(7200);
        // 이벤트 구간과 무관하게 요청의 focusSec가 저장된다
        assertThat(session.getFocusSec()).isEqualTo(5000);
        assertThat(session.getUserId()).isEqualTo(1L);
        assertThat(session.getEvents()).hasSize(2);
    }

    @Test
    void 순공_시간이_음수면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, END, -1, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 순공_시간이_총시간을_초과하면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, END, 7201, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 순공_시간은_0과_총시간_경계값을_허용한다() {
        assertThat(service.createSessions(1L, START, END, 0, List.of()).get(0).getFocusSec())
                .isEqualTo(0);
        assertThat(service.createSessions(1L, START, END, 7200, List.of())
                        .get(0)
                        .getFocusSec())
                .isEqualTo(7200);
    }

    @Test
    void 통계_날짜는_시작_시각의_한국_날짜를_따른다() {
        // 2026-07-23T16:30:00Z = KST 2026-07-24 01:30
        Instant start = Instant.parse("2026-07-23T16:30:00Z");
        Instant end = Instant.parse("2026-07-23T18:30:00Z");

        StudySession session =
                service.createSessions(1L, start, end, 3600, List.of()).get(0);

        assertThat(session.getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 순서가_뒤섞인_이벤트는_시작_시각_기준으로_정렬된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.AWAY, "2026-07-24T09:00:00Z", "2026-07-24T09:10:00Z"),
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 6300, events).get(0);

        assertThat(session.getEvents().get(0).getStatus()).isEqualTo(EventStatus.DEVICE);
        assertThat(session.getEvents().get(1).getStatus()).isEqualTo(EventStatus.AWAY);
    }

    @Test
    void 이벤트가_맞닿아_이어지는_것은_허용된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:05:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:10:00Z"));

        StudySession session =
                service.createSessions(1L, START, END, 6600, events).get(0);

        assertThat(session.getEvents()).hasSize(2);
    }

    // KST 23일 23:00 ~ 24일 01:00 (자정 경계 = 2026-07-23T15:00:00Z)
    private static final Instant CROSS_START = Instant.parse("2026-07-23T14:00:00Z");
    private static final Instant CROSS_END = Instant.parse("2026-07-23T16:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-07-23T15:00:00Z");

    @Test
    void 자정을_넘지_않으면_세션_한_개가_생성된다() {
        List<StudySession> sessions = service.createSessions(1L, START, END, 6600, List.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSessionSec()).isEqualTo(7200);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(6600);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 자정을_넘으면_두_개로_분할된다() {
        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 6000, List.of());

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getStartedAt()).isEqualTo(CROSS_START);
        assertThat(sessions.get(0).getEndedAt()).isEqualTo(MIDNIGHT);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 23));
        assertThat(sessions.get(0).getSessionSec()).isEqualTo(3600);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(3000);
        assertThat(sessions.get(1).getStartedAt()).isEqualTo(MIDNIGHT);
        assertThat(sessions.get(1).getEndedAt()).isEqualTo(CROSS_END);
        assertThat(sessions.get(1).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(sessions.get(1).getSessionSec()).isEqualTo(3600);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(3000);
    }

    @Test
    void 자정_분할_시_순공_시간은_조각_길이에_비례해_배분된다() {
        // KST 23일 22:00 ~ 24일 01:00 — 조각 길이 2시간:1시간
        Instant start = Instant.parse("2026-07-23T13:00:00Z");
        Instant end = Instant.parse("2026-07-23T16:00:00Z");

        List<StudySession> sessions = service.createSessions(1L, start, end, 9000, List.of());

        assertThat(sessions.get(0).getFocusSec()).isEqualTo(6000);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(3000);
    }

    @Test
    void 자정_분할_배분이_나누어떨어지지_않아도_순공_시간_합이_보존된다() {
        // 5401초를 3600:3600 조각에 배분 — 앞 조각은 내림(2700), 나머지는 마지막 조각이 가져간다
        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 5401, List.of());

        assertThat(sessions.get(0).getFocusSec()).isEqualTo(2700);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(2701);
    }

    @Test
    void 자정을_걸친_1초_미만_세션도_저장된다() {
        // 절삭으로 총 0초 — 0으로 나누기 없이 두 조각 모두 0초로 저장된다
        Instant start = Instant.parse("2026-07-23T14:59:59.600Z");
        Instant end = Instant.parse("2026-07-23T15:00:00.400Z");

        List<StudySession> sessions = service.createSessions(1L, start, end, 0, List.of());

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getSessionSec()).isEqualTo(0);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(0);
        assertThat(sessions.get(1).getSessionSec()).isEqualTo(0);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(0);
    }

    @Test
    void 절삭으로_조각_수용량을_넘는_순공_시간은_거부한다() {
        // 1.2초 세션이 자정에 걸치면 조각별 절삭(0초+0초) 때문에 담을 수 있는 순공 시간이 0초다
        Instant start = Instant.parse("2026-07-23T14:59:59.200Z");
        Instant end = Instant.parse("2026-07-23T15:00:00.400Z");

        assertThatThrownBy(() -> service.createSessions(1L, start, end, 1, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 자정에_걸친_이벤트는_두_조각으로_나뉜다() {
        // KST 23:50 ~ 00:10 폰 사용 → 각 세션에 10분씩 귀속
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-23T14:50:00Z", "2026-07-23T15:10:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 6000, events);

        assertThat(sessions.get(0).getEvents()).hasSize(1);
        assertThat(sessions.get(0).getEvents().get(0).getEndedAt()).isEqualTo(MIDNIGHT);
        assertThat(sessions.get(1).getEvents()).hasSize(1);
        assertThat(sessions.get(1).getEvents().get(0).getStartedAt()).isEqualTo(MIDNIGHT);
    }

    @Test
    void 정확히_자정에_끝나면_분할되지_않는다() {
        List<StudySession> sessions = service.createSessions(1L, CROSS_START, MIDNIGHT, 3600, List.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 23));
    }

    @Test
    void 정확히_자정에_시작하면_분할되지_않는다() {
        List<StudySession> sessions = service.createSessions(1L, MIDNIGHT, CROSS_END, 3600, List.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 자정에_정확히_끝나는_이벤트는_둘째_세션에_조각을_남기지_않는다() {
        List<StatusEvent> events = List.of(event(EventStatus.AWAY, "2026-07-23T14:50:00Z", "2026-07-23T15:00:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, events);

        assertThat(sessions.get(0).getEvents()).hasSize(1);
        assertThat(sessions.get(1).getEvents()).isEmpty();
    }

    @Test
    void 자정을_걸친_이십오시간_세션은_원본_기준으로_거부된다() {
        // KST 23일 19:00 ~ 24일 20:00 (25시간) — 조각별로는 24시간 이내지만 원본이 한도 초과
        Instant start = Instant.parse("2026-07-23T10:00:00Z");
        Instant end = Instant.parse("2026-07-24T11:00:00Z");

        assertThatThrownBy(() -> service.createSessions(1L, start, end, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 겹치는_이벤트는_자정을_넘는_세션에서도_거부된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.PHONE, "2026-07-23T14:10:00Z", "2026-07-23T14:30:00Z"),
                event(EventStatus.AWAY, "2026-07-23T14:20:00Z", "2026-07-23T14:40:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, CROSS_START, CROSS_END, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 세션의_집중률을_계산한다() {
        StudySession session =
                service.createSessions(1L, START, END, 6600, List.of()).get(0);

        // 6600 / 7200 × 100 = 91.66... → 91.7
        assertThat(StudySessionService.focusRate(session.getFocusSec(), session.getSessionSec()))
                .isEqualTo(91.7);
    }

    @Test
    void 집중률은_소수_한_자리로_반올림된다() {
        assertThat(StudySessionService.focusRate(1, 3)).isEqualTo(33.3);
        assertThat(StudySessionService.focusRate(2, 3)).isEqualTo(66.7);
        assertThat(StudySessionService.focusRate(7200, 7200)).isEqualTo(100.0);
        assertThat(StudySessionService.focusRate(0, 7200)).isEqualTo(0.0);
    }

    @Test
    void 총시간이_0이면_집중률은_0이다() {
        assertThat(StudySessionService.focusRate(0, 0)).isEqualTo(0.0);
    }

    @Test
    void 종료가_시작보다_빠르거나_같으면_거부한다() {
        assertThatThrownBy(() -> service.createSessions(1L, START, START, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 이십사_시간을_초과하는_세션은_거부한다() {
        Instant start = NOW.minusSeconds(60 * 60 * 25);
        Instant end = start.plusSeconds(60 * 60 * 24 + 1);

        assertThatThrownBy(() -> service.createSessions(1L, start, end, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 종료_시각이_허용_오차를_넘는_미래이면_거부한다() {
        Instant end = NOW.plusSeconds(60 * 6); // now + 6분 (허용 오차 5분 초과)

        assertThatThrownBy(() -> service.createSessions(1L, START, end, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 이벤트의_종료가_시작보다_빠르거나_같으면_거부한다() {
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:30:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 세션_구간을_벗어난_이벤트는_거부한다() {
        List<StatusEvent> events = List.of(event(EventStatus.AWAY, "2026-07-24T09:50:00Z", "2026-07-24T10:10:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 겹치는_이벤트는_거부한다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.DEVICE, "2026-07-24T08:00:00Z", "2026-07-24T08:10:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:15:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, START, END, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void date로_조회하면_그_날짜_하루_기간으로_조회한다() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(studySessionRepository.findByUserIdAndStatDateBetweenOrderByStartedAtDesc(1L, date, date))
                .thenReturn(List.of());
        when(studySessionRepository.countEventsByStatus(1L, date, date)).thenReturn(List.of());

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
        when(studySessionRepository.countEventsByStatus(1L, date, date)).thenReturn(List.of());

        StudySessionListResponse response = service.list(1L, date);

        assertThat(response.sessionCount()).isEqualTo(2);
        assertThat(response.totalSessionSec()).isEqualTo(10800);
        assertThat(response.totalFocusSec()).isEqualTo(9600);
    }
}
