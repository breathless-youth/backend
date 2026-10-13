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
import project.study.metrics.report.fact.SessionFact;
import project.study.studysession.service.StudySessionMetricsService;

/** 리포트 v2 세션 사실 조회 — 10분 미만 포함, 일시정지 수, 소셜(방 참여 겹침) 판정 (BY-904). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SessionFactQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2020, 2, 10);

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

    private long insertSession(long userId, LocalDate statDate, Instant startedAt, Instant endedAt, int focusSec) {
        return jdbcTemplate.queryForObject(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?) returning id",
                Long.class,
                userId,
                java.sql.Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(endedAt),
                focusSec,
                focusSec);
    }

    private long insertSession(long userId, Instant startedAt, Instant endedAt) {
        return insertSession(userId, DAY, startedAt, endedAt, 600);
    }

    private void insertEvent(long sessionId, String status) {
        jdbcTemplate.update(
                "insert into status_event (session_id, status, started_at, ended_at) values (?, ?, ?, ?)",
                sessionId,
                status,
                Timestamp.from(at(1)),
                Timestamp.from(at(1).plusSeconds(60)));
    }

    // 닫힌 방으로 넣는다 — 열린 방 코드 부분 유니크 인덱스와 다른 테스트가 충돌하지 않게
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
        return DAY.atStartOfDay(KST).toInstant().plusSeconds(hour * 3600L);
    }

    private List<SessionFact> factsOf(long userId) {
        return studySessionMetricsService.findSessionFacts(DAY, DAY).stream()
                .filter(fact -> fact.userId() == userId)
                .toList();
    }

    private boolean socialOf(long userId) {
        return factsOf(userId).getFirst().social();
    }

    @Test
    void 기간_안_세션을_10분_미만까지_시작_시각과_함께_돌려준다() {
        long userId = insertUser();
        insertSession(userId, DAY, at(1), at(2), 599);
        insertSession(userId, DAY, at(3), at(4), 1200);
        insertSession(userId, DAY.plusDays(1), at(25), at(26), 900);

        assertThat(factsOf(userId))
                .extracting(SessionFact::statDate, SessionFact::startedAt, SessionFact::focusSec)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DAY, at(1), 599),
                        org.assertj.core.groups.Tuple.tuple(DAY, at(3), 1200));
    }

    @Test
    void 일시정지_이벤트만_센다() {
        long userId = insertUser();
        long sessionId = insertSession(userId, at(1), at(3));
        insertEvent(sessionId, "PAUSE");
        insertEvent(sessionId, "PAUSE");
        insertEvent(sessionId, "PHONE");

        assertThat(factsOf(userId).getFirst().pauseCount()).isEqualTo(2);
    }

    @Test
    void 룸_참여구간_안의_세션은_소셜이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(0), at(4));

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 룸_참여가_없는_세션은_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 부분만_겹쳐도_소셜이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(2), at(5));

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 아직_진행_중인_참여도_소셜이다() {
        // left_at NULL은 "지금 방에 있음"이다 (BY-626) — 밤새 방에 있던 유저의 어제 세션도 소셜이다
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(2), null);

        assertThat(socialOf(userId)).isTrue();
    }

    @Test
    void 세션_종료_후_시작된_진행_중_참여는_소셜이_아니다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(5), null);

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 겹치지_않는_참여는_싱글이다() {
        long userId = insertUser();
        insertSession(userId, at(1), at(3));
        insertParticipation(insertRoom(userId), userId, at(5), at(6));

        assertThat(socialOf(userId)).isFalse();
    }

    @Test
    void 다른_유저의_참여는_소셜로_치지_않는다() {
        long owner = insertUser();
        long other = insertUser();
        insertSession(owner, at(1), at(3));
        insertParticipation(insertRoom(other), other, at(0), at(4));

        assertThat(socialOf(owner)).isFalse();
    }
}
