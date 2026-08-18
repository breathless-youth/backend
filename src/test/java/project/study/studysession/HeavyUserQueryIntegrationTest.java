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
import project.study.metrics.dto.HeavyUser;
import project.study.studysession.service.StudySessionMetricsService;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class HeavyUserQueryIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 다른 테스트가 쓰지 않는 과거 날짜 — 전체 집계 쿼리라 데이터가 섞이면 안 된다
    private static final LocalDate ANCHOR = LocalDate.of(2020, 1, 10);
    private static final int QUALIFYING = 600; // 10분 — 스트릭 인정 기준
    private static final int BELOW = 599;

    @Autowired
    private StudySessionMetricsService studySessionMetricsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int sequence = 0;

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (provider, provider_user_id, created_at, updated_at) "
                        + "values ('DEVICE', ?, now(), now()) returning id",
                Long.class,
                UUID.randomUUID().toString());
    }

    /**
     * started_at은 (user_id, started_at) 유니크 제약과, 기간이 겹치면 안 되는 무겹침 제약(V9)이 있어
     * 호출마다 세션 길이(1시간)만큼 밀어 앞 세션과 맞닿기만 하게 한다(반개구간이라 겹침이 아니다).
     */
    private void insertSession(long userId, LocalDate statDate, int focusSec) {
        Instant startedAt = statDate.atStartOfDay(KST).toInstant().plusSeconds(3600L * sequence++);
        jdbcTemplate.update(
                "insert into study_session (user_id, stat_date, started_at, ended_at, study_sec, focus_sec) "
                        + "values (?, ?, ?, ?, ?, ?)",
                userId,
                java.sql.Date.valueOf(statDate),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plusSeconds(3600)),
                focusSec,
                focusSec);
    }

    private List<Long> heavyUserIds() {
        return studySessionMetricsService.findHeavyUsers(ANCHOR).stream()
                .map(HeavyUser::userId)
                .toList();
    }

    @Test
    void 인정일이_3일이면_헤비유저다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(2), QUALIFYING);

        assertThat(heavyUserIds()).contains(userId);
    }

    @Test
    void 인정일이_2일이면_헤비유저가_아니다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 순공시간이_599초인_세션은_인정되지_않는다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, BELOW);
        insertSession(userId, ANCHOR.minusDays(1), BELOW);
        insertSession(userId, ANCHOR.minusDays(2), BELOW);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 같은_날_10분_미만_세션이_여러_개여도_10분_이상_세션이_하나_있으면_그_날은_인정된다() {
        // ADR-0009의 "하나라도" 규칙 — 하루 합계가 아니라 세션 단위 기준이다
        long userId = insertUser();
        insertSession(userId, ANCHOR, BELOW);
        insertSession(userId, ANCHOR, BELOW);
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(2), QUALIFYING);

        assertThat(heavyUserIds()).contains(userId);
    }

    @Test
    void 같은_날_인정_세션이_여러_개여도_하루로_센다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR, QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 구간_시작일인_어제_빼기_6일은_포함된다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(6), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(heavyUserIds()).contains(userId);
    }

    @Test
    void 구간_밖인_어제_빼기_7일은_제외된다() {
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(7), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(5), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 앵커보다_뒤인_오늘_세션은_집계에_들어가지_않는다() {
        // 발송 시각(오전 10시) 기준 오늘은 부분 집계라 제외한다는 결정을 고정한다
        long userId = insertUser();
        insertSession(userId, ANCHOR.plusDays(1), QUALIFYING);
        insertSession(userId, ANCHOR, QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(1), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 자정_분할로_조각난_세션은_각_조각이_10분_미만이면_인정되지_않는다() {
        // ADR-0005의 자정 분할은 focusSec을 비율 배분하므로 12분 세션이 5분+7분이 된다.
        // 병합하지 않는 것은 앱 스트릭(ADR-0009)과 같은 판정을 유지하려는 의도적 선택이다 —
        // 누군가 병합 로직을 넣으면 이 테스트가 실패해 불일치를 알린다.
        long userId = insertUser();
        insertSession(userId, ANCHOR.minusDays(1), 300);
        insertSession(userId, ANCHOR, 420);
        insertSession(userId, ANCHOR.minusDays(3), QUALIFYING);
        insertSession(userId, ANCHOR.minusDays(4), QUALIFYING);

        assertThat(heavyUserIds()).doesNotContain(userId);
    }

    @Test
    void 인정일수가_많은_유저가_먼저_온다() {
        long lessActive = insertUser();
        insertSession(lessActive, ANCHOR, QUALIFYING);
        insertSession(lessActive, ANCHOR.minusDays(1), QUALIFYING);
        insertSession(lessActive, ANCHOR.minusDays(2), QUALIFYING);

        long moreActive = insertUser();
        for (int i = 0; i < 5; i++) {
            insertSession(moreActive, ANCHOR.minusDays(i), QUALIFYING);
        }

        List<HeavyUser> heavyUsers = studySessionMetricsService.findHeavyUsers(ANCHOR).stream()
                .filter(u -> u.userId() == moreActive || u.userId() == lessActive)
                .toList();

        assertThat(heavyUsers).extracting(HeavyUser::userId).containsExactly(moreActive, lessActive);
        assertThat(heavyUsers.get(0).activeDays()).isEqualTo(5L);
        assertThat(heavyUsers.get(1).activeDays()).isEqualTo(3L);
    }
}
