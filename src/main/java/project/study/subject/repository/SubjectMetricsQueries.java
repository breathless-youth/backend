package project.study.subject.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;

/** 일일 리포트 v2가 읽는 과목·할 일 사실 (BY-904). */
@Repository
@RequiredArgsConstructor
public class SubjectMetricsQueries {

    private final JdbcClient jdbc;

    /** 유저들의 과목 전부(지운 것 포함). 빈 목록이면 IN ()이 문법 오류라 바로 돌려준다. */
    public List<SubjectFact> findSubjectsOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT user_id, created_at, deleted_at FROM study_subject WHERE user_id IN (:ids)")
                .param("ids", userIds)
                .query((rs, rowNum) ->
                        new SubjectFact(rs.getLong("user_id"), instant(rs, "created_at"), instant(rs, "deleted_at")))
                .list();
    }

    /** {@code [from, to)}에 완료한 할 일을 과목 주인 유저로. 지운 할 일도 완료 기록이라 센다. */
    public List<UserEventFact> findTasksDoneBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT s.user_id, t.done_at FROM study_task t
                        JOIN study_subject s ON s.id = t.subject_id
                        WHERE t.done_at >= :from AND t.done_at < :to""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> new UserEventFact(rs.getLong("user_id"), instant(rs, "done_at")))
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
