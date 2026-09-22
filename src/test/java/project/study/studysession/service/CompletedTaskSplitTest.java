package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.dto.SubjectLookup;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

/** 완료 할 일의 자정 분할 귀속 (ADR-0022) — 비례 배분이 아니라 완료 시각이 속한 조각 하나에 붙는지. */
@ExtendWith(MockitoExtension.class)
class CompletedTaskSplitTest {

    // 고정 현재 시각: 2026-07-24T12:00:00Z (KST 21:00)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC);

    private static final Instant START = Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-24T10:00:00Z");

    // KST 23일 23:00 ~ 24일 01:00 (자정 경계 = 2026-07-23T15:00:00Z)
    private static final Instant CROSS_START = Instant.parse("2026-07-23T14:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-07-23T15:00:00Z");
    private static final Instant CROSS_END = Instant.parse("2026-07-23T16:00:00Z");

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(
                studySessionRepository,
                activeStudySessionRepository,
                CLOCK,
                (subjectIds, taskIds) -> SubjectLookup.EMPTY);
    }

    private static CompletedTask task(long id, Instant doneAt) {
        return new CompletedTask(id, doneAt);
    }

    private static SessionAttachments completed(List<CompletedTask> tasks) {
        return new SessionAttachments(List.of(), tasks);
    }

    @Test
    void 자정을_넘지_않으면_완료_시각이_없어도_전부_한_조각에_붙는다() {
        List<StudySession> sessions = service.validateAndBuildSessions(
                1L,
                START,
                END,
                7200,
                6000,
                List.of(),
                completed(List.of(task(1, START.plusSeconds(60)), task(2, null))));

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getCompletedTaskIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void 자정을_넘으면_완료_시각이_속한_조각에_붙고_없거나_범위_밖이면_마지막_조각이다() {
        List<CompletedTask> tasks = List.of(
                task(1, CROSS_START.plusSeconds(1800)), // 23:30 → 첫 조각
                task(2, MIDNIGHT), // 정확히 자정 → 반개구간이라 둘째 조각
                task(3, MIDNIGHT.plusSeconds(1800)), // 00:30 → 둘째 조각
                task(4, null), // 완료 해제 뒤 제출 → 마지막 조각
                task(5, CROSS_START.minusSeconds(3600)), // 세션 시작 전 → 마지막 조각
                task(6, CROSS_END)); // 종료 시각과 같음 → 범위 밖 → 마지막 조각

        List<StudySession> sessions =
                service.validateAndBuildSessions(1L, CROSS_START, CROSS_END, 7200, 6000, List.of(), completed(tasks));

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getCompletedTaskIds()).containsExactly(1L);
        assertThat(sessions.get(1).getCompletedTaskIds()).containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void 완료_할_일이_없으면_조각마다_빈_집합이고_기존_오버로드도_그대로다() {
        List<StudySession> sessions =
                service.validateAndBuildSessions(1L, CROSS_START, CROSS_END, 7200, 6000, List.of(), List.of());

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getCompletedTaskIds()).isEmpty();
        assertThat(sessions.get(1).getCompletedTaskIds()).isEmpty();
    }
}
