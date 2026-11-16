package project.study.mascot.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.mascot.dto.Mascot;

@Repository
@RequiredArgsConstructor
public class MascotRepository {

    private final JdbcClient jdbc;

    /** 해금 기준 오름차순. */
    public List<Mascot> findAll() {
        return jdbc.sql("SELECT code, unlock_focus_sec, price FROM mascot ORDER BY sort_order, code")
                .query((rs, n) -> new Mascot(
                        rs.getString("code"), rs.getInt("unlock_focus_sec"), rs.getObject("price", Integer.class)))
                .list();
    }

    /** 측정한 기록의 누적 순공. 직접 입력을 넣으면 입력만으로 마스코트를 열 수 있어 뺀다. */
    public long totalFocusSec(long userId) {
        return jdbc.sql(
                        "SELECT COALESCE(SUM(focus_sec), 0) FROM study_session WHERE user_id = :u AND source = 'MEASURED'")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    // 선택해 둔 마스코트가 나중에 포인트로 사는 것으로 바뀌면, 사지 않은 사람의 선택은 없는 것으로 본다
    private static final String USABLE_SELECTION = """
            SELECT s.user_id, s.selected_mascot_code FROM user_mascot s
              JOIN mascot m ON m.code = s.selected_mascot_code
             WHERE (m.price IS NULL OR EXISTS (
                       SELECT 1 FROM user_mascot_owned o
                        WHERE o.user_id = s.user_id AND o.mascot_code = m.code))
            """;

    /** 고른 마스코트. 고른 적이 없거나, 고른 것이 사지 않은 포인트 마스코트면 빈 값. */
    public Optional<String> selectedCode(long userId) {
        return jdbc.sql("SELECT selected_mascot_code FROM (" + USABLE_SELECTION + ") usable WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .optional();
    }

    /** 쓸 수 있는 마스코트를 고른 사람만 담긴다. */
    public Map<Long, String> selectedCodes(Collection<Long> userIds) {
        Map<Long, String> codes = new HashMap<>();
        if (userIds.isEmpty()) {
            return codes;
        }
        jdbc.sql(USABLE_SELECTION + " AND s.user_id IN (:ids)")
                .param("ids", userIds)
                .query((rs, n) -> codes.put(rs.getLong("user_id"), rs.getString("selected_mascot_code")))
                .list();
        return codes;
    }

    public void saveSelected(long userId, String code) {
        jdbc.sql("""
                INSERT INTO user_mascot (user_id, selected_mascot_code) VALUES (:u, :code)
                ON CONFLICT (user_id) DO UPDATE SET selected_mascot_code = EXCLUDED.selected_mascot_code""").param("u", userId).param("code", code).update();
    }

    /** 포인트로 산 마스코트. */
    public Set<String> ownedCodes(long userId) {
        return jdbc.sql("SELECT mascot_code FROM user_mascot_owned WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .set();
    }

    public void insertOwned(long userId, String code) {
        jdbc.sql("INSERT INTO user_mascot_owned (user_id, mascot_code) VALUES (:u, :code)")
                .param("u", userId)
                .param("code", code)
                .update();
    }

    public void insertOwnedIfAbsent(long userId, String code) {
        jdbc.sql("INSERT INTO user_mascot_owned (user_id, mascot_code) VALUES (:u, :code) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("code", code)
                .update();
    }

    public Set<String> seenCodes(long userId) {
        return jdbc.sql("SELECT mascot_code FROM user_mascot_seen WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .set();
    }

    public void markSeen(long userId, Collection<String> codes) {
        for (String code : codes) {
            jdbc.sql("INSERT INTO user_mascot_seen (user_id, mascot_code) VALUES (:u, :code) ON CONFLICT DO NOTHING")
                    .param("u", userId)
                    .param("code", code)
                    .update();
        }
    }
}
