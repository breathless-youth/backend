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
        return jdbc.sql("SELECT code, unlock_focus_sec FROM mascot ORDER BY sort_order, code")
                .query((rs, n) -> new Mascot(rs.getString("code"), rs.getInt("unlock_focus_sec")))
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

    public Optional<String> selectedCode(long userId) {
        return jdbc.sql("SELECT selected_mascot_code FROM user_mascot WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .optional();
    }

    /** 고른 적이 있는 사람만 담긴다. */
    public Map<Long, String> selectedCodes(Collection<Long> userIds) {
        Map<Long, String> codes = new HashMap<>();
        if (userIds.isEmpty()) {
            return codes;
        }
        jdbc.sql("SELECT user_id, selected_mascot_code FROM user_mascot WHERE user_id IN (:ids)")
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
