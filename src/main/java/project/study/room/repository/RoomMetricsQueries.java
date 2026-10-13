package project.study.room.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.metrics.report.fact.UserEventFact;

/** 일일 리포트 v2가 읽는 방 참여 사실 (BY-904). 쓰기·락 경로인 RoomParticipationRepository와 나눈다. */
@Repository
@RequiredArgsConstructor
public class RoomMetricsQueries {

    private final JdbcClient jdbc;

    /** 확정된 참여(joined_at 있음) 중 {@code [from, to)}에 들어온 것. 다시 들어오면 새 행이라 한 번씩 센다. */
    public List<UserEventFact> findJoinsBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT user_id, joined_at FROM room_participations
                        WHERE joined_at >= :from AND joined_at < :to""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> new UserEventFact(
                        rs.getLong("user_id"),
                        rs.getObject("joined_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
