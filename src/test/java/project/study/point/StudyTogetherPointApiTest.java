package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 구간별·혼자/같이 공부 포인트 (BY-1298). 숫자는 기본 설정 그대로다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StudyTogetherPointApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Test
    void 방에_다른_사람이_있던_시간만_같이_값을_받는다() {
        long user = fixture.member();
        long other = fixture.member();
        Instant start = Instant.parse("2026-11-12T01:00:00Z");
        long room = room(user, start);
        // 다른 사람은 30분 뒤에 나갔다. 그 뒤 30분은 혼자다
        seat(room, user, start.minusSeconds(60), start.plusSeconds(4000));
        seat(room, other, start.minusSeconds(600), start.plusSeconds(1800));

        submit(user, start, 3600);

        assertThat(balance(user)).isEqualTo(10 + 7);
    }

    @Test
    void 방에_혼자_있었거나_방_밖에서_공부하면_혼자_값이다() {
        long user = fixture.member();
        Instant start = Instant.parse("2026-11-12T01:00:00Z");
        seat(room(user, start), user, start.minusSeconds(60), start.plusSeconds(4000));

        submit(user, start, 3600);

        assertThat(balance(user)).isEqualTo(14);
        assertThat(mvc.get()
                        .uri("/api/points/earn")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.study.tiers[0].soloPoints", v -> assertThat(v).isEqualTo(7))
                .hasPathSatisfying(
                        "$.study.tiers[0].togetherPoints", v -> assertThat(v).isEqualTo(10))
                .hasPathSatisfying("$.study.tiers.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.study.tiers[0].toSec", v -> assertThat(v).isEqualTo(36000))
                .hasPathSatisfying("$.study.maxPoints", v -> assertThat(v).isEqualTo(200))
                .hasPathSatisfying(
                        "$.dailyGoal.tiers[0].points", v -> assertThat(v).isEqualTo(20))
                .hasPathSatisfying("$.dailyGoal.capSec", v -> assertThat(v).isEqualTo(28800))
                .hasPathSatisfying("$.attendance.points", v -> assertThat(v).isEqualTo(15))
                .hasPathSatisfying("$.ad.points", v -> assertThat(v).isEqualTo(20))
                .hasPathSatisfying(
                        "$.medalPoints.daily", v -> assertThat(v).asList().containsExactly(5, 3, 1))
                .hasPathSatisfying(
                        "$.medalPoints.weekly", v -> assertThat(v).asList().containsExactly(10, 5, 3))
                .hasPathSatisfying(
                        "$.medalPoints.monthly", v -> assertThat(v).asList().containsExactly(150, 100, 50));
    }

    private long room(long owner, Instant at) {
        return jdbc.sql("""
                        INSERT INTO rooms (invite_code, created_by, created_at, closed_at, close_reason)
                        VALUES ('ZZZZ', :u, :at, :closed, 'LAST_LEFT') RETURNING id""")
                .param("u", owner)
                .param("at", Timestamp.from(at.minusSeconds(3600)))
                .param("closed", Timestamp.from(at.plusSeconds(7200)))
                .query(Long.class)
                .single();
    }

    private void seat(long room, long user, Instant joined, Instant left) {
        jdbc.sql("""
                INSERT INTO room_participations (room_id, user_id, reserved_at, joined_at, left_at, leave_reason)
                VALUES (:r, :u, :j, :j, :l, 'EXPLICIT')""")
                .param("r", room)
                .param("u", user)
                .param("j", Timestamp.from(joined))
                .param("l", Timestamp.from(left))
                .update();
    }

    private void submit(long user, Instant start, int focusSec) {
        String body = """
                {"startedAt":"%s","endedAt":"%s","studySec":%d,"focusSec":%d,"events":[],"timeZone":"Asia/Seoul"}""".formatted(start, start.plusSeconds(focusSec), focusSec, focusSec);
        assertThat(mvc.post()
                        .uri("/api/study-sessions")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private int balance(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }
}
