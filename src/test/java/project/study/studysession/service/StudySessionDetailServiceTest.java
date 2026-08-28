package project.study.studysession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import project.study.common.NotFoundException;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.entity.StatusEvent;
import project.study.studysession.entity.StudySession;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.repository.StudySessionRepository;

@ExtendWith(MockitoExtension.class)
class StudySessionDetailServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private StudySessionRepository studySessionRepository;

    @Mock
    private ActiveStudySessionRepository activeStudySessionRepository;

    private StudySessionService service;

    @BeforeEach
    void setUp() {
        service = new StudySessionService(studySessionRepository, activeStudySessionRepository, CLOCK);
    }

    @Test
    void 세션을_이벤트구간까지_반환한다() {
        Instant start = Instant.parse("2026-08-27T00:12:00Z");
        Instant end = Instant.parse("2026-08-27T01:36:00Z");
        StudySession session = new StudySession(
                1L,
                LocalDate.of(2026, 8, 27),
                start,
                end,
                5040,
                4080,
                List.of(new StatusEvent(
                        EventStatus.PHONE,
                        Instant.parse("2026-08-27T00:34:00Z"),
                        Instant.parse("2026-08-27T00:41:00Z"))));
        when(studySessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));

        StudySessionResponse response = service.findById(1L, 10L);

        assertThat(response.statDate()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(response.focusRate()).isEqualTo(81.0); // 4080/5040*100 → 81.0
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).status()).isEqualTo(EventStatus.PHONE);
    }

    @Test
    void 없거나_남의세션이면_404() {
        when(studySessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L, 99L)).isInstanceOf(NotFoundException.class);
    }
}
