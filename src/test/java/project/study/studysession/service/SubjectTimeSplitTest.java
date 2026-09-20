package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Instant;
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
import project.study.studysession.entity.StudySessionSubjectTime;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/** 과목·할 일별 시간의 검증과 자정 분할 배분 (ADR-0021) — 세션 배분과 같은 가중치를 쓰는지, 합이 보존되는지. */
@ExtendWith(MockitoExtension.class)
class SubjectTimeSplitTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00)
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    // KST 23일 23:00 ~ 24일 01:00 (자정 경계 = 2026-07-23T15:00:00Z) — 조각 길이 3600/3600
    private static final Instant CROSS_START = Instant.parse("2026-07-23T14:00:00Z");
    private static final Instant CROSS_END = Instant.parse("2026-07-23T16:00:00Z");

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    private static StudySessionSubjectTime time(long subjectId, Long taskId, int studySec, int focusSec) {
        return new StudySessionSubjectTime(subjectId, taskId, studySec, focusSec);
    }

    @Test
    void 자정을_넘지_않으면_항목_시간이_그대로_한_조각에_담긴다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 7200, 6000, List.of(), List.of(time(1, 5L, 4000, 3500), time(2, null, 2000, 1800)));

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSubjectTimes())
                .extracting(
                        StudySessionSubjectTime::getSubjectId,
                        StudySessionSubjectTime::getTaskId,
                        StudySessionSubjectTime::getStudySec,
                        StudySessionSubjectTime::getFocusSec)
                .containsExactly(tuple(1L, 5L, 4000, 3500), tuple(2L, null, 2000, 1800));
    }

    @Test
    void 항목_시간이_없으면_기존과_같이_저장된다() {
        List<StudySession> sessions = service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of());

        assertThat(sessions.get(0).getSubjectTimes()).isEmpty();
    }

    @Test
    void 자정을_넘으면_항목_시간도_조각_길이에_비례해_나뉘고_합이_보존된다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                CROSS_START,
                CROSS_END,
                7200,
                6000,
                List.of(),
                List.of(time(1, null, 6000, 5000), time(2, 7L, 1200, 600)));

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getSubjectTimes())
                .extracting(StudySessionSubjectTime::getStudySec, StudySessionSubjectTime::getFocusSec)
                .containsExactly(tuple(3000, 2500), tuple(600, 300));
        assertThat(sessions.get(1).getSubjectTimes())
                .extracting(
                        StudySessionSubjectTime::getSubjectId,
                        StudySessionSubjectTime::getTaskId,
                        StudySessionSubjectTime::getStudySec,
                        StudySessionSubjectTime::getFocusSec)
                .containsExactly(tuple(1L, null, 3000, 2500), tuple(2L, 7L, 600, 300));
    }

    @Test
    void 일시정지가_한쪽에_몰리면_항목_시간도_세션과_같은_가중치로_치우친다() {
        // 첫 조각(23:00~00:00)에 PAUSE 30분 → 가중치 1800 : 3600
        StatusEvent pause = new StatusEvent(
                EventStatus.PAUSE, Instant.parse("2026-07-23T14:30:00Z"), Instant.parse("2026-07-23T15:00:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, CROSS_START, CROSS_END, 5400, 5400, List.of(pause), List.of(time(1, null, 5400, 5400)));

        assertThat(sessions.get(0).getStudySec()).isEqualTo(1800);
        assertThat(sessions.get(0).getSubjectTimes().get(0).getStudySec()).isEqualTo(1800);
        assertThat(sessions.get(0).getSubjectTimes().get(0).getFocusSec()).isEqualTo(1800);
        assertThat(sessions.get(1).getSubjectTimes().get(0).getStudySec()).isEqualTo(3600);
        assertThat(sessions.get(1).getSubjectTimes().get(0).getFocusSec()).isEqualTo(3600);
    }

    @Test
    void 조각_몫이_0인_항목은_행을_만들지_않는다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, CROSS_START, CROSS_END, 7200, 6000, List.of(), List.of(time(1, null, 1, 1)));

        assertThat(sessions.get(0).getSubjectTimes()).isEmpty();
        assertThat(sessions.get(1).getSubjectTimes())
                .extracting(StudySessionSubjectTime::getStudySec, StudySessionSubjectTime::getFocusSec)
                .containsExactly(tuple(1, 1));
    }

    @Test
    void 항목_총공부_합이_세션_총공부를_넘으면_거절한다() {
        List<StudySessionSubjectTime> over = List.of(time(1, null, 5000, 4000), time(2, null, 3000, 2000));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), over))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("항목별 총 공부 시간의 합");
    }

    @Test
    void 항목_순공이_항목_총공부를_넘으면_거절한다() {
        List<StudySessionSubjectTime> bad = List.of(time(1, null, 1000, 1001));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), bad))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("항목별 순공 시간");
    }
}
