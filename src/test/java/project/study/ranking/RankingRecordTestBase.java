package project.study.ranking;

import static project.study.support.AuthTestSupport.asUser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

/** 마감 기록 API 테스트 공통 (BY-828) — 기록·마감 표시·개인 최고를 SQL로 바로 넣고, 새 경로 기본 버전(1)으로 요청한다. */
abstract class RankingRecordTestBase extends RankingIntegrationTestBase {

    @Autowired
    protected MockMvcTester mvc;

    // 새 경로라 기본버전 1이다 — asUser의 기본 헤더(2)를 덮는다 (ADR-0015 갱신)
    protected MockMvcRequestBuilder get(String uri, long userId) {
        return mvc.get()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId));
    }

    protected MockMvcRequestBuilder post(String uri, long userId, String json) {
        return mvc.post()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected long record(
            long userId, String boardKey, LocalDate periodStart, Instant closesAt, int rank, String value) {
        return jdbc.queryForObject(
                """
                INSERT INTO ranking_record (user_id, board_key, period_start, closes_at, rank, value)
                VALUES (?, ?, ?, ?, ?, ?::numeric) RETURNING id""", Long.class, userId, boardKey, periodStart, closesAt.atOffset(ZoneOffset.UTC), rank, value);
    }

    protected void closed(String boardKey, LocalDate periodStart, Instant closesAt, boolean skipped) {
        jdbc.update(
                "INSERT INTO ranking_close (board_key, period_start, closes_at, closed_at, skipped) VALUES (?, ?, ?, ?, ?)",
                boardKey,
                periodStart,
                closesAt.atOffset(ZoneOffset.UTC),
                closesAt.plusSeconds(65).atOffset(ZoneOffset.UTC),
                skipped);
    }

    protected void best(long userId, int rank, String boardKey, LocalDate periodStart) {
        jdbc.update(
                "INSERT INTO ranking_best (user_id, rank, board_key, period_start) VALUES (?, ?, ?, ?)",
                userId,
                rank,
                boardKey,
                periodStart);
    }
}
