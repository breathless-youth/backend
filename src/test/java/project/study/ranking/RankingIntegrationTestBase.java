package project.study.ranking;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import project.study.TestcontainersConfiguration;
import project.study.ranking.engine.StandingsCache;
import project.study.room.support.MutableClock;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.StudySessionService;

/**
 * 랭킹 통합테스트 공통 기반 (BY-828). 랭킹은 전체 사용자를 집계하므로 다른 테스트의 데이터가 섞이면 순위가 흔들린다 —
 * 이 클래스의 시계 설정이 컨텍스트 키를 갈라 랭킹 테스트만 쓰는 컨테이너가 뜨고, 매 테스트 전에 그 데이터를 비운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RankingIntegrationTestBase.RankingClockConfig.class})
public abstract class RankingIntegrationTestBase {

    protected static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-10-10(토) 15:00 KST — 주간은 10/5(월)~10/11(일), 지금 구간은 오후다. */
    protected static final Instant NOW =
            ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, KST).toInstant();

    @TestConfiguration
    static class RankingClockConfig {

        @Bean
        @Primary
        MutableClock rankingClock() {
            return MutableClock.at(NOW);
        }
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StandingsCache standingsCache;

    @Autowired
    protected StudySessionService studySessionService;

    @Autowired
    protected ActiveStudySessionRepository activeStudySessionRepository;

    @BeforeEach
    void resetRankingState() {
        jdbc.execute("TRUNCATE users, active_study_session RESTART IDENTITY CASCADE");
        standingsCache.clear();
        clock.set(NOW);
    }

    protected static Instant kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, KST).toInstant();
    }

    protected long user(String nickname) {
        return jdbc.queryForObject(
                "INSERT INTO users (provider, provider_user_id, nickname) VALUES ('test', ?, ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname);
    }

    protected void withdraw(long userId) {
        jdbc.update("UPDATE users SET status = 'DELETE' WHERE id = ?", userId);
    }

    /** 확정 세션 — 실제 제출 경로(자정 분할·구간 행)를 그대로 탄다. 총공부는 길이 전체, 순공은 focusSec. */
    protected void session(long userId, Instant start, int minutes, int focusSec) {
        Instant end = start.plusSeconds(minutes * 60L);
        studySessionService.create(
                userId,
                new StudySessionCreateRequest(start, end, minutes * 60, focusSec, List.of(), null, null),
                false);
    }

    /** 진행 중 스냅샷 — lastSeenAt = reportedAt, 총공부는 길이 전체. events는 JSON 배열 문자열 */
    protected void draft(long userId, Instant start, Instant reportedAt, int focusSec, String events) {
        int length = (int) Duration.between(start, reportedAt).toSeconds();
        activeStudySessionRepository.upsertSnapshot(userId, start, reportedAt, reportedAt, length, focusSec, events);
    }
}
