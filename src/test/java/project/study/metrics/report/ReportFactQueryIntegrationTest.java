package project.study.metrics.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.dday.service.DdayMetricsService;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.metrics.report.fact.UserFact;
import project.study.room.service.RoomMetricsService;
import project.study.subject.service.SubjectMetricsService;
import project.study.user.service.UserService;

/** 리포트 v2가 user·room·subject·dday 도메인에서 읽는 사실 (BY-904). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportFactQueryIntegrationTest {

    private static final Instant T0 = Instant.parse("2020-03-01T00:00:00Z");

    @Autowired
    private UserService userService;

    @Autowired
    private RoomMetricsService roomMetricsService;

    @Autowired
    private SubjectMetricsService subjectMetricsService;

    @Autowired
    private DdayMetricsService ddayMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long insertUser(Instant createdAt, String nickname, String category, String goal) {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, nickname, category, goal, created_at, updated_at) "
                        + "values ('DEVICE', ?, ?, ?, ?, ?, now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                category,
                goal,
                createdAt == null ? null : Timestamp.from(createdAt));
    }

    private long insertUser(Instant createdAt) {
        return insertUser(createdAt, null, null, null);
    }

    private long insertClosedRoom(long createdBy) {
        return jdbcTemplate.queryForObject(
                "insert into rooms (invite_code, created_by, created_at, closed_at, close_reason) "
                        + "values ('0000', ?, now(), now(), 'LAST_LEFT') returning id",
                Long.class,
                createdBy);
    }

    private void insertParticipation(long roomId, long userId, Instant joinedAt) {
        jdbcTemplate.update(
                "insert into room_participations (room_id, user_id, reserved_at, stomp_confirmed, joined_at, left_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                roomId,
                userId,
                Timestamp.from(T0),
                joinedAt != null,
                joinedAt == null ? null : Timestamp.from(joinedAt),
                Timestamp.from(T0.plusSeconds(86_400 * 30L)));
    }

    private long insertSubject(long userId, Instant createdAt, Instant deletedAt) {
        return jdbcTemplate.queryForObject(
                "insert into study_subject (user_id, name, created_at, updated_at, deleted_at) "
                        + "values (?, '과목', ?, ?, ?) returning id",
                Long.class,
                userId,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                deletedAt == null ? null : Timestamp.from(deletedAt));
    }

    private void insertTask(long subjectId, Instant doneAt) {
        jdbcTemplate.update(
                "insert into study_task (subject_id, name, done_at) values (?, '할 일', ?)",
                subjectId,
                doneAt == null ? null : Timestamp.from(doneAt));
    }

    @Test
    void 기준_시각_뒤_가입자와_따로_지정한_유저를_가입_정보와_함께_돌려준다() {
        long recent = insertUser(T0.plusSeconds(10), "새내기", "CSAT", "수능 1등급");
        long old = insertUser(T0.minusSeconds(86_400), "옛유저", null, null);
        long untouched = insertUser(T0.minusSeconds(86_400));

        List<UserFact> users = userService.findReportUsers(T0, Set.of(old));

        assertThat(users).extracting(UserFact::userId).contains(recent, old).doesNotContain(untouched);
        assertThat(users)
                .filteredOn(user -> user.userId() == recent)
                .singleElement()
                .isEqualTo(new UserFact(recent, "새내기", T0.plusSeconds(10), "CSAT", "수능 1등급"));
    }

    @Test
    void 있는_회원번호만_돌려준다() {
        long userId = insertUser(T0);

        assertThat(userService.findExistingUserIds(Set.of(userId, Long.MAX_VALUE)))
                .containsExactly(userId);
        assertThat(userService.findExistingUserIds(Set.of())).isEmpty();
    }

    @Test
    void 가입_시각이_없는_유저는_날짜를_매길_수_없어_뺀다() {
        long noCreatedAt = insertUser(null);

        assertThat(userService.findReportUsers(T0, Set.of(noCreatedAt)))
                .extracting(UserFact::userId)
                .doesNotContain(noCreatedAt);
    }

    @Test
    void 기간_안에_확정된_방_참여만_센다() {
        long userId = insertUser(T0);
        long room = insertClosedRoom(userId);
        insertParticipation(room, userId, T0);
        insertParticipation(room, userId, T0.plusSeconds(3600));
        insertParticipation(room, userId, T0.plusSeconds(7200)); // 끝 경계 — 빠진다
        insertParticipation(room, userId, null); // 확정 안 된 예약

        List<UserEventFact> joins = roomMetricsService.findJoins(T0, T0.plusSeconds(7200));

        assertThat(joins)
                .filteredOn(join -> join.userId() == userId)
                .extracting(UserEventFact::at)
                .containsExactlyInAnyOrder(T0, T0.plusSeconds(3600));
    }

    @Test
    void 지운_과목도_생성_시각과_함께_돌려준다() {
        long userId = insertUser(T0);
        long otherUser = insertUser(T0);
        insertSubject(userId, T0, null);
        insertSubject(userId, T0.plusSeconds(60), T0.plusSeconds(120));
        insertSubject(otherUser, T0, null);

        assertThat(subjectMetricsService.findSubjectsOf(List.of(userId)))
                .containsExactlyInAnyOrder(
                        new SubjectFact(userId, T0, null),
                        new SubjectFact(userId, T0.plusSeconds(60), T0.plusSeconds(120)));
        assertThat(subjectMetricsService.findSubjectsOf(List.of())).isEmpty();
    }

    @Test
    void 기간_안에_완료한_할_일을_과목_주인_유저로_돌려준다() {
        long userId = insertUser(T0);
        long subjectId = insertSubject(userId, T0, null);
        insertTask(subjectId, T0.plusSeconds(10));
        insertTask(subjectId, T0.plusSeconds(86_400)); // 기간 밖
        insertTask(subjectId, null); // 안 끝남

        assertThat(subjectMetricsService.findTasksDone(T0, T0.plusSeconds(3600)))
                .filteredOn(done -> done.userId() == userId)
                .containsExactly(new UserEventFact(userId, T0.plusSeconds(10)));
    }

    @Test
    void D_day는_만든_시각으로_돌려준다() {
        long userId = insertUser(T0);
        jdbcTemplate.update(
                "insert into user_dday (user_id, title, target_date, created_at, updated_at) values (?, '시험', ?, ?, ?)",
                userId,
                java.sql.Date.valueOf("2020-12-01"),
                Timestamp.from(T0),
                Timestamp.from(T0));

        assertThat(ddayMetricsService.findDdaysOf(List.of(userId))).containsExactly(new UserEventFact(userId, T0));
        assertThat(ddayMetricsService.findDdaysOf(List.of())).isEmpty();
    }
}
