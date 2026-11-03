package project.study.subject;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.studysession.service.ActiveStudySessionService;
import project.study.subject.service.StudySubjectService;
import project.study.user.account.AccountData;
import project.study.user.account.UserDataFixture;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SubjectUserDataHandlerTest {

    private static final Instant T0 = Instant.parse("2030-03-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2030-03-01T11:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2030, 3, 1);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    AccountData accountData;

    @Autowired
    UserDataFixture fixture;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StudySubjectService subjectService;

    @Autowired
    ActiveStudySessionService activeStudySessionService;

    @Test
    void 이름이_같은_과목은_하나로_합치고_구간과_할_일이_남는_쪽_과목을_가리킨다() {
        String nfd = Normalizer.normalize("수학", Normalizer.Form.NFD);
        for (String sourceName : List.of("수학 ", nfd)) {
            long source = fixture.seedUser();
            long target = fixture.seedUser();
            long targetSubject = fixture.seedSubject(target, "수학", 0, false);
            long sourceSubject = fixture.seedSubject(source, sourceName, 0, false);
            long session = fixture.seedSession(source, T0, T1);
            fixture.seedSegment(session, sourceSubject, T0, T1);
            fixture.seedTask(sourceSubject, "문제집");

            accountData.merge(source, target);

            // 옮기는 쪽 과목은 지우지 않고 남는 쪽의 지운 과목이 된다 — 진행 중 세션이 아직 그 ID를 보낼 수 있다
            assertThat(liveSubjectIds(target)).containsExactly(targetSubject);
            assertThat(jdbc.sql("SELECT user_id FROM study_subject WHERE id = :id AND deleted_at IS NOT NULL")
                            .param("id", sourceSubject)
                            .query(Long.class)
                            .single())
                    .isEqualTo(target);
            assertThat(fixture.count("study_session_subject_segment", "subject_id", targetSubject))
                    .isEqualTo(1);
            assertThat(fixture.count("study_task", "subject_id", targetSubject)).isEqualTo(1);
            assertThat(fixture.countRowsReferencing(source)).isZero();
        }
    }

    @Test
    void 합친_과목을_가리키던_진행_중_기록은_남는_쪽_과목으로_확정되고_옛_과목_ID도_계속_받는다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        long targetSubject = fixture.seedSubject(target, "수학", 0, false);
        long sourceSubject = fixture.seedSubject(source, "수학", 0, false);
        Instant start =
                LocalDate.now(KST).minusDays(1).atStartOfDay(KST).plusHours(1).toInstant();
        long draftId = fixture.seedDraftWithSegment(source, start, start.plusSeconds(1800), sourceSubject);

        accountData.merge(source, target);

        assertThat(jdbc.sql("SELECT subject_segments ->> 0 FROM active_study_session WHERE id = :id")
                        .param("id", draftId)
                        .query(String.class)
                        .single())
                .contains("\"subjectId\": " + targetSubject);
        // 병합 직후에도 앱은 옛 과목 ID로 하트비트·최종 제출을 보낸다 — 남는 쪽 소유라 400이 아니다
        subjectService.assertOwned(target, List.of(sourceSubject));

        activeStudySessionService.finalizeDraft(draftId);

        List<Long> segmentSubjects = jdbc.sql("""
                        SELECT g.subject_id FROM study_session_subject_segment g
                        JOIN study_session s ON s.id = g.session_id
                        WHERE s.user_id = :u AND s.started_at = :start""")
                .param("u", target)
                .param("start", Timestamp.from(start))
                .query(Long.class)
                .list();
        assertThat(segmentSubjects).containsExactly(targetSubject);
    }

    private List<Long> liveSubjectIds(long userId) {
        return jdbc.sql("SELECT id FROM study_subject WHERE user_id = :u AND deleted_at IS NULL ORDER BY id")
                .param("u", userId)
                .query(Long.class)
                .list();
    }

    @Test
    void 이름이_다른_과목은_남는_쪽_뒤_순서로_옮긴다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        fixture.seedSubject(target, "국어", 0, false);
        fixture.seedSubject(target, "영어", 1, false);
        fixture.seedSubject(source, "물리", 5, false);
        fixture.seedSubject(source, "화학", 3, false);
        fixture.seedSubject(source, "지운 과목", 4, true);

        accountData.merge(source, target);

        List<String> names = jdbc.sql(
                        "SELECT name FROM study_subject WHERE user_id = :u AND deleted_at IS NULL ORDER BY sort_order")
                .param("u", target)
                .query(String.class)
                .list();
        assertThat(names).containsExactly("국어", "영어", "화학", "물리");
        List<Integer> orders = jdbc.sql(
                        "SELECT sort_order FROM study_subject WHERE user_id = :u AND deleted_at IS NULL ORDER BY sort_order")
                .param("u", target)
                .query(Integer.class)
                .list();
        assertThat(orders).containsExactly(0, 1, 2, 3);
        assertThat(fixture.count("study_subject", "user_id", target)).isEqualTo(5);
        assertThat(fixture.countRowsReferencing(source)).isZero();
    }

    @Test
    void 합친_과목의_같은_날_완료_기록은_하나만_남는다() {
        long source = fixture.seedUser();
        long target = fixture.seedUser();
        long targetSubject = fixture.seedSubject(target, "수학", 0, false);
        long sourceSubject = fixture.seedSubject(source, "수학", 0, false);
        long sourceOnly = fixture.seedSubject(source, "과학", 1, false);
        fixture.seedDailyCompletion(target, targetSubject, DAY);
        fixture.seedDailyCompletion(source, sourceSubject, DAY);
        fixture.seedDailyCompletion(source, sourceSubject, DAY.plusDays(1));
        fixture.seedDailyCompletion(source, sourceOnly, DAY);

        accountData.merge(source, target);

        assertThat(fixture.count("daily_completion", "user_id", source)).isZero();
        assertThat(fixture.count("daily_completion", "target_id", targetSubject))
                .isEqualTo(2);
        assertThat(fixture.count("daily_completion", "target_id", sourceOnly)).isEqualTo(1);
        assertThat(fixture.count("daily_completion", "user_id", target)).isEqualTo(3);
    }

    @Test
    void erase하면_과목과_할_일과_완료_기록이_사라지고_다른_사용자는_그대로다() {
        long user = fixture.seedUser();
        long other = fixture.seedUser();
        long subject = fixture.seedSubject(user, "수학", 0, false);
        fixture.seedTask(subject, "t");
        fixture.seedDailyCompletion(user, subject, DAY);
        long otherSubject = fixture.seedSubject(other, "수학", 0, false);
        fixture.seedTask(otherSubject, "t");
        fixture.seedDailyCompletion(other, otherSubject, DAY);

        accountData.erase(user);

        assertThat(fixture.count("study_subject", "user_id", user)).isZero();
        assertThat(fixture.count("daily_completion", "user_id", user)).isZero();
        assertThat(fixture.count("study_task", "subject_id", subject)).isZero();
        assertThat(fixture.count("study_subject", "user_id", other)).isEqualTo(1);
        assertThat(fixture.count("study_task", "subject_id", otherSubject)).isEqualTo(1);
        assertThat(fixture.count("daily_completion", "user_id", other)).isEqualTo(1);
    }
}
