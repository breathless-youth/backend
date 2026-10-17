package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import project.study.studysession.dto.StudySessionResponse;
import project.study.studysession.dto.StudySessionSummaryResponse;
import project.study.studysession.dto.SubjectLookup;
import project.study.studysession.entity.StudySession;

class StudySessionResponseBundleKeyTest {

    @Test
    void 묶음_키가_없는_옛_세션은_자기_시작_시각을_묶음_키로_내린다() {
        Instant start = Instant.parse("2026-07-24T01:00:00Z");
        StudySession session =
                new StudySession(1L, LocalDate.of(2026, 7, 24), start, start.plusSeconds(3600), 3600, 3600, List.of());
        session.attachToSubmission(null);

        assertThat(StudySessionSummaryResponse.from(session, 0.0, Map.of(), SubjectLookup.EMPTY)
                        .submissionStartedAt())
                .isEqualTo(start);
        assertThat(StudySessionResponse.from(session, 0.0, SubjectLookup.EMPTY).submissionStartedAt())
                .isEqualTo(start);
    }
}
