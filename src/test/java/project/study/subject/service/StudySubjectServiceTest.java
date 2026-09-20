package project.study.subject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.common.exception.BadRequestException;
import project.study.studysession.dto.SubjectTimeRequest;
import project.study.studysession.repository.StudySessionSubjectTimeRepository;
import project.study.subject.dto.SubjectResponse;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.entity.StudySubject;
import project.study.subject.entity.StudyTask;
import project.study.subject.repository.StudySubjectRepository;
import project.study.subject.repository.StudyTaskRepository;

/** 상한·소유 검증·완료 토글의 순수 규칙 — 저장 경로와 누적 합산은 StudySubjectApiTest가 검증한다. */
@ExtendWith(MockitoExtension.class)
class StudySubjectServiceTest {

    // 고정 현재 시각: 2026-09-20T03:00:00Z (KST 12:00)
    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private StudySubjectRepository subjectRepository;

    @Mock
    private StudyTaskRepository taskRepository;

    @Mock
    private StudySessionSubjectTimeRepository subjectTimeRepository;

    private StudySubjectService service;

    @BeforeEach
    void setUp() {
        service = new StudySubjectService(subjectRepository, taskRepository, subjectTimeRepository, CLOCK);
    }

    @Test
    void 살아있는_과목이_20개면_추가를_거절한다() {
        when(subjectRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(1L, "수학")).isInstanceOf(BadRequestException.class);
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void 과목_이름은_앞뒤_공백을_잘라_저장한다() {
        when(subjectRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(0L);
        when(subjectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SubjectResponse response = service.create(1L, "  수학 ");

        assertThat(response.name()).isEqualTo("수학");
        assertThat(response.tasks()).isEmpty();
        assertThat(response.studySec()).isZero();
    }

    @Test
    void 다른_사용자의_과목이_섞이면_소유_검증이_거절한다() {
        when(subjectRepository.findByIdInAndUserId(Set.of(5L), 1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.assertOwned(1L, List.of(new SubjectTimeRequest(5L, null, 100, 90))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 할_일이_다른_과목_소속이면_거절한다() {
        StudySubject owned = mock(StudySubject.class);
        when(owned.getId()).thenReturn(5L);
        when(subjectRepository.findByIdInAndUserId(Set.of(5L), 1L)).thenReturn(List.of(owned));
        StudyTask task = mock(StudyTask.class);
        when(task.getId()).thenReturn(9L);
        when(task.getSubjectId()).thenReturn(6L);
        when(taskRepository.findByIdIn(Set.of(9L))).thenReturn(List.of(task));

        assertThatThrownBy(() -> service.assertOwned(1L, List.of(new SubjectTimeRequest(5L, 9L, 100, 90))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 과목과_할_일이_모두_사용자의_것이면_통과한다() {
        StudySubject owned = mock(StudySubject.class);
        when(owned.getId()).thenReturn(5L);
        when(subjectRepository.findByIdInAndUserId(Set.of(5L), 1L)).thenReturn(List.of(owned));
        StudyTask task = mock(StudyTask.class);
        when(task.getId()).thenReturn(9L);
        when(task.getSubjectId()).thenReturn(5L);
        when(taskRepository.findByIdIn(Set.of(9L))).thenReturn(List.of(task));

        assertThatCode(() -> service.assertOwned(
                        1L, List.of(new SubjectTimeRequest(5L, 9L, 100, 90), new SubjectTimeRequest(5L, null, 50, 40))))
                .doesNotThrowAnyException();
    }

    @Test
    void 완료_처리하면_현재_시각이_기록되고_해제하면_지워진다() {
        when(subjectRepository.findByIdAndUserIdAndDeletedAtIsNull(5L, 1L))
                .thenReturn(Optional.of(new StudySubject(1L, "수학")));
        StudyTask task = new StudyTask(5L, "3단원");
        when(taskRepository.findByIdAndSubjectIdAndDeletedAtIsNull(9L, 5L)).thenReturn(Optional.of(task));
        when(subjectTimeRepository.sumByTaskIds(List.of(9L))).thenReturn(List.of());

        service.updateTask(1L, 5L, 9L, new TaskUpdateRequest(null, true));
        assertThat(task.getDoneAt()).isEqualTo(NOW);

        service.updateTask(1L, 5L, 9L, new TaskUpdateRequest(null, false));
        assertThat(task.getDoneAt()).isNull();
    }

    @Test
    void 이름과_done이_모두_없으면_거절한다() {
        assertThatThrownBy(() -> service.updateTask(1L, 5L, 9L, new TaskUpdateRequest(null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 과목을_지우면_하위_할_일도_같은_시각으로_숨긴다() {
        StudySubject subject = new StudySubject(1L, "수학");
        when(subjectRepository.findByIdAndUserIdAndDeletedAtIsNull(5L, 1L)).thenReturn(Optional.of(subject));

        service.delete(1L, 5L);

        assertThat(subject.getDeletedAt()).isEqualTo(NOW);
        verify(taskRepository).softDeleteBySubjectId(5L, NOW);
    }
}
