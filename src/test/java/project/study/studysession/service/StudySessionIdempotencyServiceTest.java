package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import project.study.common.NotFoundException;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.StudySessionRepository;

/** 재전송(강제종료 후 복구 등) 멱등 처리 — 멱등 키는 (userId, startedAt) 루트 제출, 중복이면 저장 없이 기존 결과를 반환한다. */
@ExtendWith(MockitoExtension.class)
class StudySessionIdempotencyServiceTest {

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

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, CLOCK);
    }

    private static StudySessionCreateRequest request(Instant startedAt, Instant endedAt) {
        return new StudySessionCreateRequest(1L, startedAt, endedAt, 7200, 6600, List.of());
    }

    private static StudySession session(Instant startedAt, Instant endedAt) {
        return new StudySession(1L, LocalDate.of(2026, 7, 24), startedAt, endedAt, 7200, 6600, List.of());
    }

    private void givenNoStoredSubmission(Instant startedAt) {
        when(studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(1L, startedAt))
                .thenReturn(List.of());
    }

    @Test
    void 같은_시각에_시작한_루트_제출이_있으면_저장하지_않고_기존_세션을_반환한다() {
        when(studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(1L, START))
                .thenReturn(List.of(session(START, END)));

        List<StudySessionResponse> responses = service.create(request(START, END));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).studySec()).isEqualTo(7200);
        verify(studySessionRepository, never()).saveAll(any());
    }

    @Test
    void 자정_분할_제출의_재전송이면_종료_시각이_달라도_저장된_조각을_모두_반환한다() {
        // 루트 제출 시각으로만 조회하므로 재제출 본문의 endedAt이 원본과 달라도 조각 전체가 그대로 온다
        when(studySessionRepository.findByUserIdAndSubmissionStartedAtOrderByStartedAtAsc(1L, CROSS_START))
                .thenReturn(List.of(session(CROSS_START, MIDNIGHT), session(MIDNIGHT, CROSS_END)));

        List<StudySessionResponse> responses = service.create(request(CROSS_START, MIDNIGHT));

        assertThat(responses).hasSize(2);
        verify(studySessionRepository, never()).saveAll(any());
    }

    @Test
    void 같은_루트_제출이_없으면_정상_저장한다() {
        givenNoStoredSubmission(START);
        when(studySessionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<StudySessionResponse> responses = service.create(request(START, END));

        assertThat(responses).hasSize(1);
        verify(studySessionRepository).saveAll(any());
    }

    @Test
    void 저장_시_유니크_제약_위반이면_DuplicateSessionException을_던진다() {
        // 멱등 사전 조회 이후 끼어든 동시 재전송 레이스 — flush에서 (user_id, started_at) 유니크 위반이 감지된다
        givenNoStoredSubmission(START);
        doThrow(integrityViolation("uq_study_session_user_started_at"))
                .when(studySessionRepository)
                .flush();

        assertThatThrownBy(() -> service.create(request(START, END))).isInstanceOf(DuplicateSessionException.class);
    }

    @Test
    void 저장_시_유저_FK_위반이면_NotFoundException을_던진다() {
        givenNoStoredSubmission(START);
        doThrow(integrityViolation("study_session_user_id_fkey"))
                .when(studySessionRepository)
                .flush();

        assertThatThrownBy(() -> service.create(request(START, END))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 알_수_없는_무결성_위반은_숨기지_않고_그대로_전파한다() {
        // 404/409로 위장하면 서버·데이터 오류가 "유저 없음"으로 가려진다 — 원본 예외를 전파해 500으로 드러낸다
        givenNoStoredSubmission(START);
        DataIntegrityViolationException unknown = integrityViolation("some_other_constraint");
        doThrow(unknown).when(studySessionRepository).flush();

        assertThatThrownBy(() -> service.create(request(START, END))).isSameAs(unknown);
    }

    /** 실제 저장 실패처럼 원인 체인에 Hibernate 제약 위반(제약 이름 포함)을 담은 예외를 만든다. */
    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException(), constraintName));
    }
}
