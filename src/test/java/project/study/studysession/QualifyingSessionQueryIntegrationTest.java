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

    // 이미 닫힌 방으로 넣는다 — rooms_open_code_uidx는 열린 방 전체에 걸린 부분 유니크 인덱스라,
    // 고정 코드 '0000'을 열린 채로 두면 랜덤 코드로 방을 커밋하는 다른 테스트와 충돌할 수 있다.
    // 소셜 판정은 방의 열림 여부가 아니라 참여 구간만 보므로 닫힌 방이어도 검증에는 영향이 없다.
    private long insertRoom(long createdBy) {
        return jdbcTemplate.queryForObject(
                "insert into rooms (invite_code, created_by, created_at, closed_at, close_reason) "
                        + "values ('0000', ?, now(), now(), 'LAST_LEFT') returning id",
                Long.class,
                createdBy);
    }

    private void insertParticipation(long roomId, long userId, Instant joinedAt, Instant leftAt) {
        jdbcTemplate.update(
                "insert into room_participations (room_id, user_id, reserved_at, stomp_confirmed, joined_at, left_at) "
                        + "values (?, ?, ?, true, ?, ?)",
                roomId,
                userId,
                Timestamp.from(joinedAt),
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
        long room = insertRoom(userId);
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
        long room = insertRoom(userId);
        insertParticipation(room, userId, at(2), at(5)); // 세션 뒷부분만 겹침

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 아직_진행_중인_참여도_소셜이다() {
        // BY-626 이후 left_at NULL은 "지금 방에 있음"이다(죽은 태스크의 잔재는 리스 스윕이 30초+30초 안에 닫는다).
        // 어제 저녁부터 밤새 방에 있던 유저가 아침 집계 때 아직 방에 있어도 어제 세션은 소셜이어야 한다.
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        long room = insertRoom(userId);
        insertParticipation(room, userId, at(2), null);

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 세션_종료_후_시작된_진행_중_참여는_소셜이_아니다() {
        // left_at NULL을 "지금까지 계속"으로 읽으면 세션이 끝난 뒤 시작한 참여까지 겹침이 된다 —
        // 겹침은 어디까지나 [joined_at, left_at) 구간과 세션 구간의 교집합으로만 판정해야 한다
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        long room = insertRoom(userId);
        insertParticipation(room, userId, at(5), null);

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 겹치지_않는_참여는_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3), QUALIFYING);
        long room = insertRoom(userId);
        insertParticipation(room, userId, at(5), at(6)); // 세션 이후

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 다른_유저의_참여는_소셜로_치지_않는다() {
        long owner = insertUser();
        long other = insertUser();
        insertSession(owner, at(1), at(3), QUALIFYING);
        long room = insertRoom(other);
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
