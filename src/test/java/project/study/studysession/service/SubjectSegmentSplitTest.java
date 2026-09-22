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
import project.study.studysession.dto.SubjectSegmentRequest;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.StudySessionSubjectSegment;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/** 과목 구간의 검증·자정 절단·파생 계산 (ADR-0023) — 이벤트 겹침이 총공부·순공에 정확히 빠지는지, 자정 절단과 초 미만 절삭이 규칙대로인지. */
@ExtendWith(MockitoExtension.class)
class SubjectSegmentSplitTest {

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

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    private static SubjectSegmentRequest seg(long subjectId, String start, String end) {
        return new SubjectSegmentRequest(subjectId, Instant.parse(start), Instant.parse(end));
    }

    private static StatusEvent event(EventStatus status, String start, String end) {
        return new StatusEvent(status, Instant.parse(start), Instant.parse(end));
    }

    @Test
    void 이벤트가_없으면_구간_길이가_곧_총공부이자_순공이고_시작_시각_순으로_정렬된다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                START,
                END,
                7200,
                7200,
                List.of(),
                List.of(
                        seg(2, "2026-07-24T09:00:00Z", "2026-07-24T09:30:00Z"),
                        seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z")));

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSubjectSegments())
                .extracting(
                        StudySessionSubjectSegment::getSubjectId,
                        StudySessionSubjectSegment::getStudySec,
                        StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(1L, 3600, 3600), tuple(2L, 1800, 1800));
    }

    @Test
    void 구간이_없으면_기존과_같이_저장된다() {
        List<StudySession> sessions = service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of());

        assertThat(sessions.get(0).getSubjectSegments()).isEmpty();
    }

    @Test
    void 일시정지_겹침은_총공부와_순공에서_모두_빠지고_다른_이벤트_겹침은_순공에서만_빠진다() {
        // 구간 08:00~09:00. PAUSE 08:10~08:20(10분), PHONE 08:40~08:45(5분), 구간 밖 AWAY 09:30~09:40
        List<StatusEvent> events = List.of(
                event(EventStatus.PAUSE, "2026-07-24T08:10:00Z", "2026-07-24T08:20:00Z"),
                event(EventStatus.PHONE, "2026-07-24T08:40:00Z", "2026-07-24T08:45:00Z"),
                event(EventStatus.AWAY, "2026-07-24T09:30:00Z", "2026-07-24T09:40:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 6600, 5700, events, List.of(seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z")));

        StudySessionSubjectSegment segment =
                sessions.get(0).getSubjectSegments().get(0);
        assertThat(segment.getStudySec()).isEqualTo(3600 - 600);
        assertThat(segment.getFocusSec()).isEqualTo(3600 - 600 - 300);
    }

    @Test
    void 이벤트가_구간_경계에_걸치면_겹친_만큼만_뺀다() {
        // 구간 08:30~09:00, PHONE 08:20~08:40 → 겹침 10분
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-24T08:20:00Z", "2026-07-24T08:40:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 7200, 6000, events, List.of(seg(1, "2026-07-24T08:30:00Z", "2026-07-24T09:00:00Z")));

        StudySessionSubjectSegment segment =
                sessions.get(0).getSubjectSegments().get(0);
        assertThat(segment.getStudySec()).isEqualTo(1800);
        assertThat(segment.getFocusSec()).isEqualTo(1800 - 600);
    }

    @Test
    void 이벤트가_구간을_완전히_덮으면_0초_행이_남는다() {
        List<StatusEvent> events = List.of(event(EventStatus.PAUSE, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L, START, END, 3600, 3600, events, List.of(seg(1, "2026-07-24T08:10:00Z", "2026-07-24T08:50:00Z")));

        assertThat(sessions.get(0).getSubjectSegments())
                .extracting(StudySessionSubjectSegment::getStudySec, StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(0, 0));
    }

    @Test
    void 자정을_넘으면_구간이_자정에서_잘리고_조각마다_그_조각의_이벤트로_계산된다() {
        // 구간 23:30~00:30(KST). 첫 조각에만 PHONE 23:40~23:50(10분)
        List<StatusEvent> events = List.of(event(EventStatus.PHONE, "2026-07-23T14:40:00Z", "2026-07-23T14:50:00Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                CROSS_START,
                CROSS_END,
                7200,
                6600,
                events,
                List.of(seg(1, "2026-07-23T14:30:00Z", "2026-07-23T15:30:00Z")));

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getSubjectSegments())
                .extracting(
                        StudySessionSubjectSegment::getStartedAt,
                        StudySessionSubjectSegment::getEndedAt,
                        StudySessionSubjectSegment::getStudySec,
                        StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(Instant.parse("2026-07-23T14:30:00Z"), MIDNIGHT, 1800, 1200));
        assertThat(sessions.get(1).getSubjectSegments())
                .extracting(
                        StudySessionSubjectSegment::getStartedAt,
                        StudySessionSubjectSegment::getEndedAt,
                        StudySessionSubjectSegment::getStudySec,
                        StudySessionSubjectSegment::getFocusSec)
                .containsExactly(tuple(MIDNIGHT, Instant.parse("2026-07-23T15:30:00Z"), 1800, 1800));
    }

    @Test
    void 정확히_자정에_끝나는_구간은_첫_조각에만_남고_둘째_조각에는_0초_조각을_만들지_않는다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                CROSS_START,
                CROSS_END,
                7200,
                7200,
                List.of(),
                List.of(seg(1, "2026-07-23T14:00:00Z", "2026-07-23T15:00:00Z")));

        assertThat(sessions.get(0).getSubjectSegments()).hasSize(1);
        assertThat(sessions.get(1).getSubjectSegments()).isEmpty();
    }

    @Test
    void 구간이_서로_겹치면_거절한다() {
        List<SubjectSegmentRequest> overlapping = List.of(
                seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z"),
                seg(2, "2026-07-24T08:59:00Z", "2026-07-24T09:30:00Z"));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), overlapping))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("과목 구간이 서로 겹칠 수 없습니다");
    }

    @Test
    void 맞닿는_구간은_허용한다() {
        List<SubjectSegmentRequest> touching = List.of(
                seg(1, "2026-07-24T08:00:00Z", "2026-07-24T09:00:00Z"),
                seg(2, "2026-07-24T09:00:00Z", "2026-07-24T09:30:00Z"));

        assertThat(service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), touching)
                        .get(0)
                        .getSubjectSegments())
                .hasSize(2);
    }

    @Test
    void 구간이_세션_밖이면_거절한다() {
        List<SubjectSegmentRequest> outside = List.of(seg(1, "2026-07-24T07:50:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), outside))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("과목 구간은 세션 구간 안에 있어야 합니다");
    }

    @Test
    void 종료가_시작_이후가_아닌_구간은_거절한다() {
        List<SubjectSegmentRequest> zero = List.of(seg(1, "2026-07-24T08:30:00Z", "2026-07-24T08:30:00Z"));

        assertThatThrownBy(() -> service.validateAndBuildSessions(1L, START, END, 7200, 6000, List.of(), zero))
                .isInstanceOf(InvalidSessionException.class)
                .hasMessageContaining("과목 구간 종료 시각은 시작 시각 이후여야 합니다");
    }

    @Test
    void 초_미만_조각은_절삭되어_행을_만들지_않는다() {
        // 14:59:59.6 ~ 15:00:00.4 (자정 걸침) — 조각이 각 0.4초라 절삭 뒤 0초. 이벤트 절삭(clip)과 같은 정책이다
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                CROSS_START,
                CROSS_END,
                7200,
                7200,
                List.of(),
                List.of(seg(1, "2026-07-23T14:59:59.600Z", "2026-07-23T15:00:00.400Z")));

        assertThat(sessions.get(0).getSubjectSegments()).isEmpty();
        assertThat(sessions.get(1).getSubjectSegments()).isEmpty();
    }

    @Test
    void 초_미만_길이와_겹침은_각각_절삭된다() {
        // 구간 08:00:00.0~08:00:10.9 (10.9초 → 10초), PHONE 08:00:05.5~08:00:10.9 (겹침 5.4초 → 5초)
        List<StatusEvent> events =
                List.of(event(EventStatus.PHONE, "2026-07-24T08:00:05.500Z", "2026-07-24T08:00:10.900Z"));

        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                START,
                END,
                7200,
                7000,
                events,
                List.of(seg(1, "2026-07-24T08:00:00Z", "2026-07-24T08:00:10.900Z")));

        StudySessionSubjectSegment segment =
                sessions.get(0).getSubjectSegments().get(0);
        assertThat(segment.getStudySec()).isEqualTo(10);
        assertThat(segment.getFocusSec()).isEqualTo(10 - 5);
    }
}
