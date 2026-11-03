package project.study.group.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** 그룹 리포지토리가 함께 쓰는 JDBC 변환. 시각은 {@code Timestamp}로 주고받는다. */
final class Rows {

    private Rows() {}

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
