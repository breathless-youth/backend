package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionLongestFocusServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate DATE = LocalDate.of(2026, 7, 24);
    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
        when(studySessionRepository.findDistinctStatDatesBetween(
                        1L, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 60))
                .thenReturn(List.of());
    }

    private StatusEvent event(EventStatus status, String startedAt, String endedAt) {
        return new StatusEvent(status, Instant.parse(startedAt), Instant.parse(endedAt));
    }

    @Test
    void 이벤트가_없으면_최장집중시간은_세션_전체_길이다() {
        StudySession session = new StudySession(1L, DATE, START, END, 7200, 6600, List.of());
        when(studySessionRepository.findByUserIdAndStatDateBetweenAndFocusSecGreaterThanEqualOrderByStartedAtDesc(
                        1L, DATE, DATE, 60))
                .thenReturn(List.of(session));

        StudySessionListResponse response = service.list(1L, DATE);

        // START(08:00) ~ END(10:00), 이벤트 없음 → 전체 2시간(7200초)이 그대로 최장구간
        assertThat(response.longestFocusSec()).isEqualTo(7200);
    }

    @Test
    void 이벤트_사이_가장_긴_간격이_최장집중시간이다() {
        // 세션 시작(08:00)~05분: 300초, 사이 간격(08:10~09:50): 6000초, 마지막(09:55~10:00): 300초 → 최댓값 6000초
        List<StatusEvent> events = List.of(
                event(EventStatus.PHONE, "2026-07-24T08:05:00Z", "2026-07-24T08:10:00Z"),
                event(EventStatus.AWAY, "2026-07-24T09:50:00Z", "2026-07-24T09:55:00Z"));
        StudySession session = new StudySession(1L, DATE, START, END, 7200, 6600, events);
        when(studySessionRepository.findByUserIdAndStatDateBetweenAndFocusSecGreaterThanEqualOrderByStartedAtDesc(
                        1L, DATE, DATE, 60))
                .thenReturn(List.of(session));

        StudySessionListResponse response = service.list(1L, DATE);

        assertThat(response.longestFocusSec()).isEqualTo(6000);
    }

    @Test
    void 여러_세션_중_가장_긴_값이_하루_최장집중시간이다() {
        // 짧은 세션(1시간=3600초)과 긴 세션(3시간=10800초), 둘 다 이벤트 없음
        StudySession shorter = new StudySession(
                1L,
                DATE,
                Instant.parse("2026-07-24T08:00:00Z"),
                Instant.parse("2026-07-24T09:00:00Z"),
                3600,
                3600,
                List.of());
        StudySession longer = new StudySession(
                1L,
                DATE,
                Instant.parse("2026-07-24T12:00:00Z"),
                Instant.parse("2026-07-24T15:00:00Z"),
                10800,
                10800,
                List.of());
        when(studySessionRepository.findByUserIdAndStatDateBetweenAndFocusSecGreaterThanEqualOrderByStartedAtDesc(
                        1L, DATE, DATE, 60))
                .thenReturn(List.of(shorter, longer));

        StudySessionListResponse response = service.list(1L, DATE);

        assertThat(response.longestFocusSec()).isEqualTo(10800);
    }

    @Test
    void 세션이_없으면_최장집중시간은_0이다() {
        when(studySessionRepository.findByUserIdAndStatDateBetweenAndFocusSecGreaterThanEqualOrderByStartedAtDesc(
                        1L, DATE, DATE, 60))
                .thenReturn(List.of());

        StudySessionListResponse response = service.list(1L, DATE);

        assertThat(response.longestFocusSec()).isEqualTo(0);
    }
}
