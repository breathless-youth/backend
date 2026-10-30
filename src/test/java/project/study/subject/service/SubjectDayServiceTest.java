package project.study.subject.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.studysession.repository.StudySessionRepository;
import project.study.subject.dto.SubjectDayResponse;
import project.study.subject.entity.CompletionTarget;
import project.study.subject.entity.StudySubject;
import project.study.subject.repository.DailyCompletionRepository;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.subject.repository.StudySubjectRepository;

/** 그날 있던 과목 판단과 완료 표시의 거절·멱등 규칙 — 시간 합산은 DaySubjectTimesTest, 저장 경로는 StudySubjectDayApiTest. */
@ExtendWith(MockitoExtension.class)
class SubjectDayServiceTest {

    // 고정 현재 시각: 2026-09-20T03:00:00Z (KST 12:00)
    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 그날 창은 2026-09-19 05:00 KST ~ 2026-09-20 05:00 KST = 2026-09-18T20:00Z ~ 2026-09-19T20:00Z. */
    private static final LocalDate DAY = LocalDate.of(2026, 9, 19);

    @Mock
    private StudySubjectRepository subjectRepository;

    @Mock
    private StudySubjectLockRepository lockRepository;

    @Mock
    private StudySessionRepository sessionRepository;

    @Mock
    private DailyCompletionRepository completionRepository;

    private SubjectDayService service;

    @BeforeEach
    void setUp() {
        service = new SubjectDayService(
                subjectRepository, lockRepository, sessionRepository, completionRepository, CLOCK);
    }

    /** 엔티티에 id·createdAt setter가 없으므로 테스트에서만 리플렉션으로 채운다. */
    private static StudySubject subjectAt(long id, String createdAt, String deletedAt) {
        StudySubject subject = new StudySubject(1L, "과목" + id, (int) id, (int) id);
        ReflectionTestUtils.setField(subject, "id", id);
        ReflectionTestUtils.setField(subject, "createdAt", Instant.parse(createdAt));
        if (deletedAt != null) {
            subject.delete(Instant.parse(deletedAt));
        }
        return subject;
    }

    @Test
    void 그날_있던_과목은_그날이_끝나기_전에_만들었고_그날_시작_뒤에_지웠거나_안_지운_것이다() {
        StudySubject beforeDay = subjectAt(1, "2026-09-10T00:00:00Z", null);
        StudySubject madeAtDawn = subjectAt(2, "2026-09-19T19:59:59Z", null); // 20일 04:59 KST — 아직 19일 하루
        StudySubject madeAfterDay = subjectAt(3, "2026-09-19T20:00:00Z", null); // 20일 05:00 KST — 다음 하루
        when(subjectRepository.findByUserIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(1L))
                .thenReturn(List.of(madeAfterDay, beforeDay, madeAtDawn));
        StudySubject goneInDay = subjectAt(5, "2026-09-10T00:00:00Z", "2026-09-19T03:00:00Z"); // 19일 12:00 KST에 지움
        StudySubject goneBeforeDay = subjectAt(4, "2026-09-10T00:00:00Z", "2026-09-18T19:59:59Z"); // 19일 04:59 KST
        when(subjectRepository.findByUserIdAndDeletedAtIsNotNull(1L)).thenReturn(List.of(goneInDay, goneBeforeDay));
        when(sessionRepository.findInPeriodWithMinFocusSec(1L, DAY, DAY.plusDays(1), 0))
                .thenReturn(List.of());
        when(completionRepository.findByUserIdAndCompletedDateAndTargetType(1L, DAY, CompletionTarget.SUBJECT))
                .thenReturn(List.of());

        List<SubjectDayResponse> day = service.subjectsOn(1L, DAY);

        // 살아있는 과목은 저장 순서 그대로(3번은 빠짐), 지운 과목은 그 뒤 — 4번은 그날 시작 전에 지워서 빠짐
        assertThat(day.stream().map(SubjectDayResponse::id)).containsExactly(1L, 2L, 5L);
        assertThat(day.get(2).deleted()).isTrue();
        assertThat(day).allSatisfy(item -> {
            assertThat(item.studySec()).isZero();
            assertThat(item.completed()).isFalse();
        });
    }

    @Test
    void 미래_날짜에는_완료를_기록할_수_없다() {
        // NOW는 2026-09-20 12:00 KST — 21일은 미래
        assertThatThrownBy(() -> service.complete(1L, 1L, LocalDate.of(2026, 9, 21)))
                .isInstanceOf(BadRequestException.class);
        verify(completionRepository, never()).save(any());
    }

    @Test
    void 그날_없던_과목이나_남의_과목은_완료를_기록할_수_없다() {
        when(subjectRepository.findByIdAndUserId(3L, 1L))
                .thenReturn(Optional.of(subjectAt(3, "2026-09-19T20:00:00Z", null)));
        when(subjectRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(1L, 3L, DAY)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.complete(1L, 9L, DAY)).isInstanceOf(NotFoundException.class);
        verify(completionRepository, never()).save(any());
    }

    @Test
    void 이미_완료한_날에_다시_완료하면_저장하지_않고_지운_과목도_그날_있었으면_된다() {
        StudySubject gone = subjectAt(5, "2026-09-10T00:00:00Z", "2026-09-19T03:00:00Z");
        when(subjectRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(gone));
        when(completionRepository.existsByUserIdAndCompletedDateAndTargetTypeAndTargetId(
                        1L, DAY, CompletionTarget.SUBJECT, 5L))
                .thenReturn(false, true);

        service.complete(1L, 5L, DAY);
        service.complete(1L, 5L, DAY);

        verify(completionRepository, times(1)).save(any());
        verify(lockRepository, times(2)).lockUser(1L);
    }
}
