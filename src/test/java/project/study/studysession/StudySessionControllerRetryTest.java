package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import project.study.studysession.controller.StudySessionController;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.service.DuplicateSessionException;
import project.study.studysession.service.StudySessionService;

/**
 * BY-447 최종 리뷰 Fix 1 — 자동 확정 스케줄러와의 유니크 레이스에서 진 최종 제출이 findExistingSubmission으로
 * 바로 폴백하지 않고 create를 1회 재시도해 대체 로직을 태우는지 검증한다. 인터리빙 재현이 어려운 레이스라
 * Mockito로 배선만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class StudySessionControllerRetryTest {

    private static final Long USER_ID = 1L;
    private static final Instant STARTED_AT = Instant.parse("2026-08-27T01:00:00Z");

    @Mock
    private StudySessionService studySessionService;

    @InjectMocks
    private StudySessionController controller;

    private final StudySessionCreateRequest request =
            new StudySessionCreateRequest(USER_ID, STARTED_AT, STARTED_AT.plusSeconds(3600), 3600, 3400, List.of());

    @Test
    void 첫_create가_레이스로_지면_재시도해서_성공하면_그_결과를_반환하고_재조회는_안한다() {
        List<StudySessionResponse> retryResult = List.of();
        when(studySessionService.create(USER_ID, request))
                .thenThrow(new DuplicateSessionException("레이스 패배"))
                .thenReturn(retryResult);

        List<StudySessionResponse> response = controller.create(request);

        assertThat(response).isSameAs(retryResult);
        verify(studySessionService, times(2)).create(USER_ID, request);
        verify(studySessionService, never()).findExistingSubmission(USER_ID, STARTED_AT);
    }

    @Test
    void 재시도도_DuplicateSessionException이면_findExistingSubmission_결과를_반환한다() {
        List<StudySessionResponse> existing = List.of(new StudySessionResponse(
                1L,
                USER_ID,
                STARTED_AT.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                STARTED_AT,
                STARTED_AT.plusSeconds(3600),
                3600,
                3400,
                94.4,
                List.of()));
        when(studySessionService.create(USER_ID, request))
                .thenThrow(new DuplicateSessionException("첫 실패"))
                .thenThrow(new DuplicateSessionException("재시도도 실패"));
        when(studySessionService.findExistingSubmission(USER_ID, STARTED_AT)).thenReturn(existing);

        List<StudySessionResponse> response = controller.create(request);

        assertThat(response).isSameAs(existing);
        verify(studySessionService, times(2)).create(USER_ID, request);
    }

    @Test
    void findExistingSubmission도_비어있으면_예외가_전파된다() {
        when(studySessionService.create(USER_ID, request))
                .thenThrow(new DuplicateSessionException("첫 실패"))
                .thenThrow(new DuplicateSessionException("재시도도 실패"));
        when(studySessionService.findExistingSubmission(USER_ID, STARTED_AT)).thenReturn(List.of());

        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(DuplicateSessionException.class);
    }

    @Test
    void ObjectOptimisticLockingFailureException도_같은_재시도_흐름을_탄다() {
        List<StudySessionResponse> retryResult = List.of();
        when(studySessionService.create(USER_ID, request))
                .thenThrow(new ObjectOptimisticLockingFailureException(StudySessionCreateRequest.class, USER_ID))
                .thenReturn(retryResult);

        List<StudySessionResponse> response = controller.create(request);

        assertThat(response).isSameAs(retryResult);
        verify(studySessionService, times(2)).create(USER_ID, request);
        verify(studySessionService, never()).findExistingSubmission(USER_ID, STARTED_AT);
    }
}
