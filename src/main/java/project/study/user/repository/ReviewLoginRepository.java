package project.study.user.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 심사용 로그인 설정과 틀린 시도 (BY-1300). */
@Repository
@RequiredArgsConstructor
public class ReviewLoginRepository {

    private final JdbcClient jdbc;

    /** releasedVersion이 null이면 꺼져 있다. */
    public record Settings(
            String loginId, String passwordHash, String releasedVersion, int initialPoints, List<String> itemCodes) {}

    public Optional<Settings> find() {
        return jdbc.sql("""
                        SELECT login_id, password_hash, released_version, initial_points, initial_item_codes
                        FROM review_login WHERE id = 1""")
                .query((rs, n) -> new Settings(
                        rs.getString("login_id"),
                        rs.getString("password_hash"),
                        rs.getString("released_version"),
                        rs.getInt("initial_points"),
                        Arrays.stream(rs.getString("initial_item_codes").split(","))
                                .map(String::trim)
                                .filter(code -> !code.isEmpty())
                                .toList()))
                .optional();
    }

    /** 출시 버전이 아직 from이면 to로 바꾼다. 그 사이 사람이 바꿨으면 그대로 두고 false. */
    public boolean raiseReleasedVersion(String from, String to) {
        return jdbc.sql("""
                                UPDATE review_login SET released_version = :to, updated_at = now()
                                WHERE id = 1 AND released_version = :from""").param("from", from).param("to", to).update() == 1;
    }

    public int failuresOf(String client, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM review_login_failure WHERE client = :c AND failed_at > :since")
                .param("c", client)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    public int failuresSince(Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM review_login_failure WHERE failed_at > :since")
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** 틀린 시도를 적고 오래된 줄은 지운다. */
    public void recordFailure(String client, Instant now, Instant deleteBefore) {
        jdbc.sql("DELETE FROM review_login_failure WHERE failed_at < :before")
                .param("before", Timestamp.from(deleteBefore))
                .update();
        jdbc.sql("INSERT INTO review_login_failure (client, failed_at) VALUES (:c, :at)")
                .param("c", client)
                .param("at", Timestamp.from(now))
                .update();
    }
}
