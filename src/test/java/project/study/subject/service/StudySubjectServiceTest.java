package project.study.subject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.common.exception.BadRequestException;
import project.study.studysession.dto.CompletedTask;
import project.study.studysession.repository.StudySessionSubjectSegmentRepository;
import project.study.subject.dto.SubjectResponse;
import project.study.subject.dto.TaskUpdateRequest;
import project.study.subject.entity.StudySubject;
import project.study.subject.entity.StudyTask;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.subject.repository.StudySubjectRepository;
import project.study.subject.repository.StudyTaskRepository;

/** 상한·소유 검증·완료 토글·순서·색 배정의 순수 규칙 — 저장 경로와 누적 합산은 StudySubjectApiTest가 검증한다. */
@ExtendWith(MockitoExtension.class)
class StudySubjectServiceTest {

    // 고정 현재 시각: 2026-09-20T03:00:00Z (KST 12:00)
    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private StudySubjectRepository subjectRepository;

    @Mock
    private StudySubjectLockRepository lockRepository;

    @Mock
    private StudyTaskRepository taskRepository;

    @Mock
    private StudySessionSubjectSegmentRepository subjectSegmentRepository;

    private StudySubjectService service;

    @BeforeEach
    void setUp() {
        service = new StudySubjectService(
                subjectRepository, lockRepository, taskRepository, subjectSegmentRepository, CLOCK);
    }

    /** 엔티티에 id setter를 두지 않으므로 테스트에서만 리플렉션으로 채운다. */
    private static StudySubject subject(long id, int sortOrder, int colorIndex) {
        StudySubject subject = new StudySubject(1L, "과목" + id, sortOrder, colorIndex);
        ReflectionTestUtils.setField(subject, "id", id);
        return subject;
    }

    private static StudyTask task(long id, long subjectId) {
        StudyTask task = new StudyTask(subjectId, "할 일" + id);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private void givenLive(List<StudySubject> live) {
        when(subjectRepository.findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(1L))
                .thenReturn(live);
    }

    @Test
    void 살아있는_과목이_20개면_추가를_거절한다() {
        givenLive(IntStream.range(0, 20).mapToObj(i -> subject(i, i, i)).toList());

        assertThatThrownBy(() -> service.create(1L, "수학")).isInstanceOf(BadRequestException.class);
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void 과목_이름은_앞뒤_공백을_잘라_저장한다() {
        givenLive(List.of());
        when(subjectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SubjectResponse response = service.create(1L, "  수학 ");

        assertThat(response.name()).isEqualTo("수학");
        assertThat(response.tasks()).isEmpty();
        assertThat(response.studySec()).isZero();
        assertThat(response.colorIndex()).isZero();
    }

    @Test
    void 새_과목은_살아있는_과목의_최대_순서_다음에_붙고_덜_쓴_색을_받는다() {
        // 중간을 지워 순서 [0, 4]만 살아있고 색은 0·1·1·2가 아니라 0·2 — 개수(2)가 아니라 max+1(5)이어야 뒤에 붙는다
        givenLive(List.of(subject(10, 0, 0), subject(11, 4, 2)));
        when(subjectRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(1L, "영어");

        ArgumentCaptor<StudySubject> saved = ArgumentCaptor.forClass(StudySubject.class);
        verify(subjectRepository).save(saved.capture());
        assertThat(saved.getValue().getSortOrder()).isEqualTo(5);
        assertThat(saved.getValue().getColorIndex()).isEqualTo(1);
        // 목록을 읽기 전에 사용자 락을 잡아야 동시 생성이 같은 값을 받지 않는다
        verify(lockRepository).lockUser(1L);
    }

    @Test
    void 덜_쓴_색은_사용_횟수가_가장_적은_인덱스이고_동률이면_작은_번호다() {
        assertThat(StudySubjectService.leastUsedColor(List.of())).isZero();
        assertThat(StudySubjectService.leastUsedColor(List.of(subject(1, 0, 0), subject(2, 1, 1), subject(3, 2, 1))))
                .isEqualTo(2);
        assertThat(StudySubjectService.leastUsedColor(List.of(subject(1, 0, 1), subject(2, 1, 2))))
                .isZero();
        // 19개가 0..18을 다 쓰면 마지막 남은 19
        assertThat(StudySubjectService.leastUsedColor(
                        IntStream.range(0, 19).mapToObj(i -> subject(i, i, i)).toList()))
                .isEqualTo(19);
    }

    @Test
    void 순서_저장에_남의_과목이_섞이면_거절하고_아무것도_바꾸지_않는다() {
        StudySubject mine = subject(1, 0, 0);
        givenLive(List.of(mine));

        assertThatThrownBy(() -> service.reorder(1L, List.of(9L, 1L))).isInstanceOf(BadRequestException.class);
        assertThat(mine.getSortOrder()).isZero();
    }

    @Test
    void 순서_저장에_중복_id가_있으면_조회_전에_거절한다() {
        assertThatThrownBy(() -> service.reorder(1L, List.of(1L, 1L))).isInstanceOf(BadRequestException.class);
        verify(subjectRepository, never()).findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(any());
        verify(lockRepository, never()).lockUser(any());
    }

    @Test
    void 순서에서_빠진_과목은_기존_순서대로_뒤에_붙는다() {
        StudySubject a = subject(1, 0, 0);
        StudySubject b = subject(2, 1, 1);
        StudySubject c = subject(3, 2, 2);
        givenLive(List.of(a, b, c));

        List<SubjectResponse> responses = service.reorder(1L, List.of(3L, 1L));

        assertThat(responses).extracting(SubjectResponse::id).containsExactly(3L, 1L, 2L);
        assertThat(c.getSortOrder()).isZero();
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).isEqualTo(2);
    }

    @Test
    void 다른_사용자의_과목이_섞이면_소유_검증이_거절한다() {
        when(subjectRepository.findByIdInAndUserId(Set.of(5L), 1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.assertOwned(1L, Set.of(5L))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void 다른_사용자의_할_일이_섞이면_완료_할_일_소유_검증이_거절한다() {
        StudyTask mine = task(9L, 5L);
        when(taskRepository.findByIdInAndOwner(Set.of(9L, 77L), 1L)).thenReturn(List.of(mine));

        assertThatThrownBy(() -> service.assertTasksOwned(1L, List.of(9L, 77L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("할 일");
    }

    @Test
    void 완료_할_일_소유_검증은_자정_귀속용_완료_시각을_붙여_돌려주고_빈_목록은_조회하지_않는다() {
        StudyTask done = task(9L, 5L);
        done.markDone(NOW);
        StudyTask undone = task(10L, 5L);
        when(taskRepository.findByIdInAndOwner(Set.of(9L, 10L), 1L)).thenReturn(List.of(done, undone));

        assertThat(service.assertTasksOwned(1L, List.of(9L, 10L)))
                .containsExactly(new CompletedTask(9L, NOW), new CompletedTask(10L, null));

        assertThat(service.assertTasksOwned(1L, List.of())).isEmpty();
        verify(taskRepository, never()).findByIdInAndOwner(Set.of(), 1L);
    }

    @Test
    void 완료_처리하면_현재_시각이_기록되고_해제하면_지워진다() {
        when(subjectRepository.findByIdAndUserIdAndDeletedAtIsNull(5L, 1L)).thenReturn(Optional.of(subject(5, 0, 0)));
        StudyTask task = new StudyTask(5L, "3단원");
        when(taskRepository.findByIdAndSubjectIdAndDeletedAtIsNull(9L, 5L)).thenReturn(Optional.of(task));

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
        StudySubject subject = subject(5, 0, 0);
        when(subjectRepository.findByIdAndUserIdAndDeletedAtIsNull(5L, 1L)).thenReturn(Optional.of(subject));

        service.delete(1L, 5L);

        assertThat(subject.getDeletedAt()).isEqualTo(NOW);
        verify(taskRepository).softDeleteBySubjectId(5L, NOW);
    }
}
