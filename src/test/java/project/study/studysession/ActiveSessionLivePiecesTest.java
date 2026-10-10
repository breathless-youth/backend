package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.studysession.dto.LivePiece;
import project.study.studysession.entity.SessionSlot;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.ActiveStudySessionService;

/** 진행 중 draft를 확정과 같은 규칙으로 나누고, 집중 중이면 마지막 수신 뒤 경과만큼 늘린다 (BY-828). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveSessionLivePiecesTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private ActiveStudySessionService service;

    @Autowired
    private ActiveStudySessionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();

    private long userId;

    @BeforeEach
    void createUser() {
        userId = user();
    }

    // livePieces와 findStaleDraftIds는 전 유저 draft를 읽는다 — 여기서 만든 draft가 커밋된 채 남으면
    // ActiveSessionFinalizeTest의 단언이 실행 순서에 따라 오염되므로, 만든 유저의 draft를 매번 지운다.
    @AfterEach
    void cleanUpDrafts() {
        createdUserIds.forEach(id -> jdbc.update("DELETE FROM active_study_session WHERE user_id = ?", id));
        createdUserIds.clear();
    }

    private long user() {
        long id = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                "live-" + UUID.randomUUID());
        createdUserIds.add(id);
        return id;
    }

    private static Instant at(int day, int hour, int minute, int second) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, second, 0, KST).toInstant();
    }

    /** lastSeenAt = reportedAt인 스냅샷. 총공부는 길이 전체 */
    private void draft(long owner, Instant start, Instant reportedAt, int focusSec, String events) {
        int length = (int) Duration.between(start, reportedAt).toSeconds();
        repository.upsertSnapshot(owner, start, reportedAt, reportedAt, length, focusSec, events);
    }

    private List<LivePiece> mine(Instant asOf, long owner) {
        return service.livePieces(asOf).stream()
                .filter(p -> p.userId() == owner)
                .toList();
    }

    @Test
    void 집중_중이면_마지막_수신_뒤_경과_시간만큼_늘려_계산한다() {
        Instant asOf = at(15, 15, 0, 0);
        draft(userId, asOf.minusSeconds(600), asOf.minusSeconds(10), 590, "[]");

        assertThat(mine(asOf, userId)).singleElement().satisfies(p -> {
            assertThat(p.focusSec()).isEqualTo(600);
            assertThat(p.studySec()).isEqualTo(600);
            assertThat(p.focusing()).isTrue();
            assertThat(p.latest()).isTrue();
            assertThat(p.achievedAt()).isEqualTo(asOf);
            assertThat(p.statDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        });
    }

    @Test
    void 진행_중인_이벤트가_있으면_집중_중이_아니고_늘리지_않는다() {
        Instant asOf = at(15, 15, 0, 0);
        Instant reported = asOf.minusSeconds(10);
        String phoneUntilNow = "[{\"status\":\"PHONE\",\"startedAt\":\"%s\",\"endedAt\":\"%s\"}]"
                .formatted(reported.minusSeconds(60), reported);
        draft(userId, asOf.minusSeconds(600), reported, 530, phoneUntilNow);

        assertThat(mine(asOf, userId)).singleElement().satisfies(p -> {
            assertThat(p.focusing()).isFalse();
            assertThat(p.focusSec()).isEqualTo(530);
            assertThat(p.achievedAt()).isEqualTo(reported);
        });
    }

    @Test
    void 마지막_수신이_60초보다_오래면_집중_중이_아니다() {
        Instant asOf = at(15, 15, 0, 0);
        draft(userId, asOf.minusSeconds(600), asOf.minusSeconds(90), 510, "[]");

        assertThat(mine(asOf, userId))
                .singleElement()
                .satisfies(p -> assertThat(p.focusing()).isFalse());
    }

    @Test
    void 자정을_걸치면_날짜별_조각으로_나누고_마지막_조각만_latest다() {
        Instant reported = at(16, 0, 30, 0);
        draft(userId, at(15, 23, 30, 0), reported, 3600, "[]");

        assertThat(mine(reported, userId))
                .extracting(LivePiece::statDate, LivePiece::focusSec, LivePiece::latest)
                .containsExactly(
                        tuple(LocalDate.of(2026, 9, 15), 1800, false), tuple(LocalDate.of(2026, 9, 16), 1800, true));
    }

    @Test
    void 늘린_시간이_구간_경계를_넘으면_새_구간에_들어간다() {
        // 06:43:00 시작, 06:59:40 보고(순공 1000초) → 07:00:20에 40초를 늘리면 오전에 20초
        draft(userId, at(15, 6, 43, 0), at(15, 6, 59, 40), 1000, "[]");

        assertThat(mine(at(15, 7, 0, 20), userId))
                .singleElement()
                .satisfies(p -> assertThat(p.slots())
                        .containsExactlyInAnyOrder(
                                new SessionSlot(TimeSlot.DAWN, LocalDate.of(2026, 9, 15), 1020),
                                new SessionSlot(TimeSlot.MORNING, LocalDate.of(2026, 9, 15), 20)));
    }

    @Test
    void 읽지_못하는_draft는_건너뛰고_나머지는_계산한다() {
        Instant asOf = at(15, 15, 0, 0);
        long broken = user();
        draft(broken, asOf.minusSeconds(600), asOf.minusSeconds(10), 590, "{\"bad\":1}");
        draft(userId, asOf.minusSeconds(600), asOf.minusSeconds(10), 590, "[]");

        assertThat(mine(asOf, broken)).isEmpty();
        assertThat(mine(asOf, userId)).hasSize(1);
    }
}
