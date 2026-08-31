package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.metrics.dto.QualifyingSession;
import project.study.studysession.service.StudySessionMetricsService;

/** 10분 이상 세션의 소셜(룸 겹침) 판별 쿼리 통합테스트. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class QualifyingSessionQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate ANCHOR = LocalDate.of(2020, 2, 10);
    private static final int QUALIFYING = 600;

    @Autowired
    private StudySessionMetricsService studySessionMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString());
    }

    /** started_at/ended_at을 명시해 룸 참여구간과의 겹침을 통제한다. */
    private void insertSession(long userId, Instant startedAt, Instant endedAt, int focusSec) {
        jdbcTemplate.update(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(ANCHOR),
                Timestamp.from(startedAt),
                Timestamp.from(endedAt),
                focusSec,
                focusSec);
    }

    private UUID insertRoom(long createdBy) {
        UUID roomUid = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into rooms (room_uid, created_by, created_at) values (?, ?, now())", roomUid, createdBy);
        return roomUid;
    }

    private void insertParticipation(UUID roomUid, long userId, Instant joinedAt, Instant leftAt) {
        jdbcTemplate.update(
                "insert into room_participations (room_uid, user_id, joined_at, left_at) values (?, ?, ?, ?)",
                roomUid,
                userId,
                Timestamp.from(joinedAt),
                leftAt == null ? null : Timestamp.from(leftAt));
    }

    private Instant at(int hour) {
        return ANCHOR.atStartOfDay(KST).toInstant().plusSeconds(hour * 3600L);
    }

    private boolean socialOf(long userId) {
        return studySessionMetricsService.findQualifyingSessions(ANCHOR).stream()
                .filter(session -> session.userId() == userId)
                .map(QualifyingSession::social)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 룸_참여구간_안의_세션은_소셜이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        UUID room = insertRoom(userId);
        insertParticipation(room, userId, at(0), at(4));

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 룸_참여가_없는_세션은_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 부분만_겹쳐도_소셜이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        UUID room = insertRoom(userId);
        insertParticipation(room, userId, at(2), at(5)); // 세션 뒷부분만 겹침

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 종료시각이_없는_stale_참여는_소셜로_치지_않는다() {
        // 서버가 룸 도중 재시작하면 left_at이 NULL로 남는다 — BY-415 설계문서는 이를
        // "비정상 종료 구간"으로 규정하고 분석에서 제외하라고 명시한다. 리포트는 어제 세션을
        // 다음날 오전 10시에 집계하므로, 그 시점에 아직 열린(NULL) 참여는 사실상 전부 이 잔재다.
        // 제외하지 않으면 그 유저의 이후 모든 세션이 매일 소셜로 오분류된다.
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        UUID room = insertRoom(userId);
        insertParticipation(room, userId, at(2), null); // left_at 없음 = 재시작 잔재

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 겹치지_않는_참여는_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        UUID room = insertRoom(userId);
        insertParticipation(room, userId, at(5), at(6)); // 세션 이후

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 다른_유저의_참여는_소셜로_치지_않는다() {
        long owner = insertUser();
        long other = insertUser();
        insertSession(owner, at(1), at(3), QUALIFYING);
        UUID room = insertRoom(other);
        insertParticipation(room, other, at(0), at(4));

        assertThat(socialOf(owner)).isFalse();
    }

    @Test
    void 순공_10분_미만_세션은_조회되지_않는다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3), 599);

        List<QualifyingSession> sessions = studySessionMetricsService.findQualifyingSessions(ANCHOR).stream()
                .filter(session -> session.userId() == userId)
                .toList();

        assertThat(sessions).isEmpty();
    }
}
