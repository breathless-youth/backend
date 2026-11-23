package project.study.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.push.dto.PushTokenRequest;
import project.study.push.entity.PushPlatform;
import project.study.push.repository.PointsShopReminderQueries;
import project.study.push.repository.PushExperimentQueries;
import project.study.push.repository.PushTokenRepository;
import project.study.push.service.PushTokenService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PointsShopReminderQueriesTest {

    private static final Instant FIRST_VISIT = Instant.parse("2026-11-22T12:00:00Z");
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 23);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PointsShopReminderQueries queries;

    @Autowired
    PushExperimentQueries sends;

    @Autowired
    PushTokenRepository tokens;

    @Test
    void 실제_토큰_등록은_캠페인과_시스템_알림을_켰을_때만_등록한다() {
        long enabled = optedInUser();
        long denied = optedInUser();
        long flagOff = optedInUser();
        Clock clock = Clock.fixed(FIRST_VISIT, ZoneOffset.UTC);
        var runningCampaign = new PushTokenService(tokens, queries, clock, true);
        var stoppedCampaign = new PushTokenService(tokens, queries, clock, false);

        runningCampaign.register(enabled, registration(true));
        runningCampaign.register(denied, registration(false));
        stoppedCampaign.register(flagOff, registration(true));

        assertThat(queries.findDue(FIRST_DAY)).contains(enabled).doesNotContain(denied, flagOff);
    }

    @Test
    void 첫_접속_다음날부터_대상이며_재접속해도_첫_발송일을_미루지_않는다() {
        long user = optedInUser();
        queries.enroll(user, FIRST_VISIT);
        queries.enroll(user, FIRST_VISIT.plusSeconds(86_400));

        assertThat(queries.findDue(FIRST_DAY.minusDays(1))).doesNotContain(user);
        assertThat(queries.findDue(FIRST_DAY)).contains(user);
    }

    @Test
    void 단계가_달라져도_마지막_리마인드_접수일부터_사흘을_센다() {
        long user = optedInUser();
        queries.enroll(user, FIRST_VISIT);
        UUID pushId = UUID.randomUUID();
        sends.reserveSend(pushId, user, "points_shop_reminder_not_started", FIRST_DAY, FIRST_VISIT.plusSeconds(86_400));
        sends.recordAccepted(pushId, 1);

        assertThat(queries.findDue(FIRST_DAY)).doesNotContain(user);
        assertThat(queries.findDue(FIRST_DAY.plusDays(2))).doesNotContain(user);
        assertThat(queries.findDue(FIRST_DAY.plusDays(3))).contains(user);
    }

    @Test
    void 토큰_권한을_끄거나_그날_다른_푸시를_보냈으면_제외한다() {
        long optedOut = optedInUser();
        long alreadySent = optedInUser();
        queries.enroll(optedOut, FIRST_VISIT);
        queries.enroll(alreadySent, FIRST_VISIT);
        jdbc.update("UPDATE push_token SET notifications_enabled = false WHERE user_id = ?", optedOut);
        sends.reserveSend(UUID.randomUUID(), alreadySent, "rhythm_reminder_usual", FIRST_DAY, FIRST_VISIT);

        assertThat(queries.findDue(FIRST_DAY)).doesNotContain(optedOut, alreadySent);
    }

    @Test
    void 접수에_실패한_첫_푸시는_당일_재발송하지_않고_다음날_다시_시도한다() {
        long user = optedInUser();
        queries.enroll(user, FIRST_VISIT);
        UUID pushId = UUID.randomUUID();
        sends.reserveSend(pushId, user, "points_shop_reminder_one_step", FIRST_DAY, FIRST_VISIT);
        sends.recordAccepted(pushId, 0);

        assertThat(queries.findDue(FIRST_DAY)).doesNotContain(user);
        assertThat(queries.findDue(FIRST_DAY.plusDays(1))).contains(user);
    }

    @Test
    void FCM_접수_결과를_저장하지_못했으면_중복을_피하도록_사흘_기다린다() {
        long user = optedInUser();
        queries.enroll(user, FIRST_VISIT);
        sends.reserveSend(UUID.randomUUID(), user, "points_shop_reminder_one_step", FIRST_DAY, FIRST_VISIT);

        assertThat(queries.findDue(FIRST_DAY.plusDays(1))).doesNotContain(user);
        assertThat(queries.findDue(FIRST_DAY.plusDays(3))).contains(user);
    }

    @Test
    void 가진_상품과_잠긴_캐릭터_상품과_타이머_배경은_추천하지_않는다() {
        long user = optedInUser();
        long accessory = insertItem("STOPWATCH", 150, true);
        insertItem("PLANNER", 50, true);
        insertItem("STOPWATCH", 20, false);
        insertItem(null, 10, true);

        assertThat(queries.affordableAccessory(user, 149)).isEmpty();
        assertThat(queries.affordableAccessory(user, 150)).contains(accessory);
        jdbc.update("INSERT INTO user_item (user_id, item_id) VALUES (?, ?)", user, accessory);
        assertThat(queries.affordableAccessory(user, 150)).isEmpty();
    }

    private long insertItem(String mascot, int price, boolean active) {
        return jdbc.queryForObject(
                """
                INSERT INTO shop_item (code, category, mascot_code, slot, name, price, active)
                VALUES (?, ?, ?, ?, '테스트 상품', ?, ?) RETURNING id
                """,
                Long.class,
                UUID.randomUUID().toString(),
                mascot == null ? "TIMER_BACKGROUND" : "MASCOT_ACCESSORY",
                mascot,
                mascot == null ? null : "HEAD",
                price,
                active);
    }

    private long optedInUser() {
        long user = jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, created_at, updated_at) VALUES ('DEVICE', ?, now(), now()) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO push_token (user_id, token, platform, notifications_enabled, created_at, updated_at)
                VALUES (?, ?, 'ANDROID', true, ?, ?)
                """, user, UUID.randomUUID().toString(), Timestamp.from(FIRST_VISIT), Timestamp.from(FIRST_VISIT));
        return user;
    }

    private PushTokenRequest registration(boolean enabled) {
        return new PushTokenRequest(UUID.randomUUID().toString(), PushPlatform.ANDROID, enabled, "ko");
    }
}
