package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/** StudySessionServiceTest에서 자정 분할·배분 관련 테스트만 분리 — 파일 길이 제한(400줄) 준수 목적. */
@ExtendWith(MockitoExtension.class)
class StudySessionMidnightSplitTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00)
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    // KST 23일 23:00 ~ 24일 01:00 (자정 경계 = 2026-07-23T15:00:00Z)
    private static final Instant CROSS_START = Instant.parse("2026-07-23T14:00:00Z");
    private static final Instant CROSS_END = Instant.parse("2026-07-23T16:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-07-23T15:00:00Z");

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    // createSessions는 순수 로직이라 리포지토리를 사용하지 않는다 — 저장 경로는 API 통합테스트가 검증
    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    private StatusEvent event(EventStatus status, String startedAt, String endedAt) {
        return new StatusEvent(status, Instant.parse(startedAt), Instant.parse(endedAt));
    }

    @Test
    void 자정을_넘지_않으면_세션_한_개가_생성된다() {
        List<StudySession> sessions = service.createSessions(1L, START, END, 7200, 6600, List.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStudySec()).isEqualTo(7200);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(6600);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 자정을_넘으면_두_개로_분할된다() {
        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, 6000, List.of());

        assertThat(sessions).hasSize(2);
        // 두 조각 모두 원본 제출의 시작 시각을 루트로 공유한다 — 재제출 판별·응답 조회의 기준
        assertThat(sessions.get(0).getSubmissionStartedAt()).isEqualTo(CROSS_START);
        assertThat(sessions.get(1).getSubmissionStartedAt()).isEqualTo(CROSS_START);
        assertThat(sessions.get(0).getStartedAt()).isEqualTo(CROSS_START);
        assertThat(sessions.get(0).getEndedAt()).isEqualTo(MIDNIGHT);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 23));
        assertThat(sessions.get(0).getStudySec()).isEqualTo(3600);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(3000);
        assertThat(sessions.get(1).getStartedAt()).isEqualTo(MIDNIGHT);
        assertThat(sessions.get(1).getEndedAt()).isEqualTo(CROSS_END);
        assertThat(sessions.get(1).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(sessions.get(1).getStudySec()).isEqualTo(3600);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(3000);
    }

    @Test
    void 자정_분할_시_총공부시간과_순공시간은_조각_길이에_비례해_배분된다() {
        // KST 23일 22:00 ~ 24일 01:00 — 조각 길이 2시간:1시간
        Instant start = Instant.parse("2026-07-23T13:00:00Z");
        Instant end = Instant.parse("2026-07-23T16:00:00Z");

        List<StudySession> sessions = service.createSessions(1L, start, end, 9000, 6000, List.of());

        assertThat(sessions.get(0).getStudySec()).isEqualTo(6000);
        assertThat(sessions.get(1).getStudySec()).isEqualTo(3000);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(4000);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(2000);
    }

    @Test
    void 자정_분할_배분이_나누어떨어지지_않아도_순공_시간_합이_보존된다() {
        // 5401초를 3600:3600 조각에 배분 — 앞 조각은 내림(2700), 나머지는 마지막 조각이 가져간다
        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, 5401, List.of());

        assertThat(sessions.get(0).getFocusSec()).isEqualTo(2700);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(2701);
    }

    @Test
    void 자정_분할_시_PAUSE_구간은_총공부시간_배분에서_제외된다() {
        // PAUSE 10분이 앞 조각(23~24시)에만 있다 — studyActiveSec: 조각1=3000, 조각2=3600, 합 6600
        List<StatusEvent> events = List.of(event(EventStatus.PAUSE, "2026-07-23T14:00:00Z", "2026-07-23T14:10:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 6600, 0, events);

        assertThat(sessions.get(0).getStudySec()).isEqualTo(3000);
        assertThat(sessions.get(1).getStudySec()).isEqualTo(3600);
    }

    @Test
    void 순공시간_분할_가중치는_PAUSE가_아닌_이벤트도_포함한다() {
        // PHONE 10분이 앞 조각에만 있다 — PAUSE가 아니므로 총공부시간 배분엔 영향 없고, 순공시간 배분에만 영향
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-23T14:00:00Z", "2026-07-23T14:10:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, 6600, events);

        assertThat(sessions.get(0).getStudySec()).isEqualTo(3600);
        assertThat(sessions.get(1).getStudySec()).isEqualTo(3600);
        // focusActiveSec: 조각1=3000, 조각2=3600, 합 6600 → 6600을 3000:3600으로 배분
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(3000);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(3600);
    }

    @Test
    void 순공시간_배분_가중치가_전부_0이면_총공부시간_비율로_대체_배분한다() {
        // PHONE이 전체 구간을 덮어 focusActiveSec 합계가 0이 되는 예외 케이스
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-23T14:00:00Z", "2026-07-23T16:00:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, 4000, events);

        assertThat(sessions.get(0).getStudySec()).isEqualTo(3600);
        assertThat(sessions.get(1).getStudySec()).isEqualTo(3600);
        // focusActiveSec 합계가 0 → studySec 비율(3600:3600)로 대체 배분
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(2000);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(2000);
    }

    @Test
    void 자정을_걸친_서브초_조각은_0초로_저장된다() {
        // 앞 조각 0.4초는 절삭으로 0초 — 0으로 나누기 없이 뒤 조각이 전부 가져간다
        Instant start = Instant.parse("2026-07-23T14:59:59.600Z");
        Instant end = Instant.parse("2026-07-23T15:10:00.000Z");

        List<StudySession> sessions = service.createSessions(1L, start, end, 600, 600, List.of());

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getStudySec()).isEqualTo(0);
        assertThat(sessions.get(0).getFocusSec()).isEqualTo(0);
        assertThat(sessions.get(1).getStudySec()).isEqualTo(600);
        assertThat(sessions.get(1).getFocusSec()).isEqualTo(600);
    }

    @Test
    void 자정_분할_시_studySec은_원본_총시간까지_허용되고_초과분만_거부한다() {
        // 601.2초 세션이 자정에 걸쳐도, 조각 길이를 합이 원본과 일치하게 반올림하므로(BY-471) 원본 정수초(601)까지
        // 담을 수 있다. 예전엔 조각별 절삭(0초+600초)으로 600만 허용돼 601이 억울하게 거부됐다.
        Instant start = Instant.parse("2026-07-23T14:59:59.200Z");
        Instant end = Instant.parse("2026-07-23T15:10:00.400Z");

        // 원본 정수초(601)까지는 허용 — 1초 손실 없음
        assertThatCode(() -> service.createSessions(1L, start, end, 601, 0, List.of()))
                .doesNotThrowAnyException();
        // 원본을 실제로 넘는 602는 거부
        assertThatThrownBy(() -> service.createSessions(1L, start, end, 602, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 자정에_걸친_이벤트는_두_조각으로_나뉜다() {
        // KST 23:50 ~ 00:10 폰 사용 → 각 세션에 10분씩 귀속
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-23T14:50:00Z", "2026-07-23T15:10:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, 6000, events);

        assertThat(sessions.get(0).getEvents()).hasSize(1);
        assertThat(sessions.get(0).getEvents().get(0).getEndedAt()).isEqualTo(MIDNIGHT);
        assertThat(sessions.get(1).getEvents()).hasSize(1);
        assertThat(sessions.get(1).getEvents().get(0).getStartedAt()).isEqualTo(MIDNIGHT);
    }

    @Test
    void 정확히_자정에_끝나면_분할되지_않는다() {
        List<StudySession> sessions = service.createSessions(1L, CROSS_START, MIDNIGHT, 3600, 3600, List.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 23));
    }

    @Test
    void 정확히_자정에_시작하면_분할되지_않는다() {
        List<StudySession> sessions = service.createSessions(1L, MIDNIGHT, CROSS_END, 3600, 3600, List.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void 자정에_정확히_끝나는_이벤트는_둘째_세션에_조각을_남기지_않는다() {
        List<StatusEvent> events = List.of(event(EventStatus.AWAY, "2026-07-23T14:50:00Z", "2026-07-23T15:00:00Z"));

        List<StudySession> sessions = service.createSessions(1L, CROSS_START, CROSS_END, 7200, 7200, events);

        assertThat(sessions.get(0).getEvents()).hasSize(1);
        assertThat(sessions.get(1).getEvents()).isEmpty();
    }

    @Test
    void 자정을_걸친_이십오시간_세션은_원본_기준으로_거부된다() {
        // KST 23일 19:00 ~ 24일 20:00 (25시간) — 조각별로는 24시간 이내지만 원본이 한도 초과
        Instant start = Instant.parse("2026-07-23T10:00:00Z");
        Instant end = Instant.parse("2026-07-24T11:00:00Z");

        assertThatThrownBy(() -> service.createSessions(1L, start, end, 0, 0, List.of()))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void 겹치는_이벤트는_자정을_넘는_세션에서도_거부된다() {
        List<StatusEvent> events = List.of(
                event(EventStatus.PHONE, "2026-07-23T14:10:00Z", "2026-07-23T14:30:00Z"),
                event(EventStatus.AWAY, "2026-07-23T14:20:00Z", "2026-07-23T14:40:00Z"));

        assertThatThrownBy(() -> service.createSessions(1L, CROSS_START, CROSS_END, 0, 0, events))
                .isInstanceOf(InvalidSessionException.class);
    }
}
