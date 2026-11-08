package project.study.mission;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.common.exception.BadRequestException;
import project.study.mission.service.HomeMissionService;
import project.study.subject.repository.StudySubjectLockRepository;

class HomeMissionServiceTest {
    @Test
    void 유효하지_않은_단계는_저장소에_접근하지_않고_거부한다() {
        JdbcClient jdbc = mock(JdbcClient.class);
        StudySubjectLockRepository locks = mock(StudySubjectLockRepository.class);
        HomeMissionService service = new HomeMissionService(jdbc, locks, java.time.LocalDate.parse("2026-11-07"));
        assertThatThrownBy(() -> service.complete(1, 3)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(jdbc, locks);
    }
}
