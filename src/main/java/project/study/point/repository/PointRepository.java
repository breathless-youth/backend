package project.study.point.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.point.dto.MedalRow;
import project.study.point.dto.PointAccount;
import project.study.point.dto.PointFund;
import project.study.point.dto.PointHistoryItem;
import project.study.point.dto.PointHistoryType;
import project.study.point.dto.PointReason;

@Repository
@RequiredArgsConstructor
public class PointRepository {

    private final JdbcClient jdbc;

    public int balance(long userId) {
        return jdbc.sql("SELECT balance FROM point_account WHERE user_id = :u")
                .param("u", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** 잔액과 그 가운데 결제 몫. 행이 없으면 둘 다 0. */
    public PointAccount account(long userId) {
        return jdbc.sql("SELECT balance, paid_balance FROM point_account WHERE user_id = :u")
                .param("u", userId)
                .query((rs, n) -> new PointAccount(rs.getInt("balance"), rs.getInt("paid_balance")))
                .optional()
                .orElse(new PointAccount(0, 0));
    }

    /** 같은 (사유, 참조 키)가 이미 있으면 넣지 않고 false. 무료 포인트만 오간 줄이다. */
    public boolean insertLedger(
            long userId, int amount, PointReason reason, String refKey, String label, LocalDate statDate) {
        return insertLedger(userId, amount, 0, reason, refKey, label, statDate);
    }

    /** paidAmount는 amount 가운데 결제 몫이고 부호가 같다. 0이 아니면 fund를 PAID로 적는다. */
    public boolean insertLedger(
            long userId,
            int amount,
            int paidAmount,
            PointReason reason,
            String refKey,
            String label,
            LocalDate statDate) {
        return jdbc.sql("""
                        INSERT INTO point_ledger (user_id, amount, paid_amount, fund, reason, ref_key, label, stat_date)
                        VALUES (:u, :amount, :paid, :fund, :reason, :ref, :label, :statDate)
                        ON CONFLICT ON CONSTRAINT uq_point_ledger_ref DO NOTHING""")
                        .param("u", userId)
                        .param("amount", amount)
                        .param("paid", paidAmount)
                        .param("fund", paidAmount == 0 ? "FREE" : "PAID")
                        .param("reason", reason.name())
                        .param("ref", refKey)
                        .param("label", label)
                        .param("statDate", statDate)
                        .update()
                > 0;
    }

    public void addBalance(long userId, int amount) {
        addBalance(userId, amount, 0);
    }

    /** paidAmount는 amount 가운데 결제 몫이다. */
    public void addBalance(long userId, int amount, int paidAmount) {
        jdbc.sql("""
                INSERT INTO point_account (user_id, balance, paid_balance) VALUES (:u, :amount, :paid)
                ON CONFLICT (user_id) DO UPDATE SET balance = point_account.balance + EXCLUDED.balance,
                    paid_balance = point_account.paid_balance + EXCLUDED.paid_balance""")
                .param("u", userId)
                .param("amount", amount)
                .param("paid", paidAmount)
                .update();
    }

    /** 포인트 사용자 락을 잡고 읽은 값에서 계산한 새 잔액을 적는다. */
    public void setAccount(long userId, PointAccount account) {
        jdbc.sql("UPDATE point_account SET balance = :b, paid_balance = :p WHERE user_id = :u")
                .param("b", account.balance())
                .param("p", account.paidBalance())
                .param("u", userId)
                .update();
    }

    /** 최신순. beforeId가 있으면 그보다 오래된 기록만. 구매 기록의 label은 그 상품의 language 이름으로 바꿔 준다. */
    public List<PointHistoryItem> history(
            long userId, PointHistoryType type, Long beforeId, int limit, String language) {
        String sign =
                switch (type) {
                    case ALL -> "";
                    case EARNED -> " AND l.amount > 0";
                    case SPENT -> " AND l.amount < 0";
                };
        String before = beforeId == null ? "" : " AND l.id < :before";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT l.id, l.amount, l.paid_amount, l.reason, l.fund, l.stat_date, l.created_at,
                               COALESCE(i.names ->> :lang, i.name, l.label) AS label
                        FROM point_ledger l
                        LEFT JOIN shop_item i ON l.reason = 'PURCHASE' AND l.ref_key = 'item:' || i.id
                        WHERE l.user_id = :u AND l.reason <> 'STUDY_ADJUST'""" + sign + before + " ORDER BY l.id DESC LIMIT :limit")
                .param("u", userId)
                .param("lang", language)
                .param("limit", limit);
        if (beforeId != null) {
            spec = spec.param("before", beforeId);
        }
        return spec.query((rs, n) -> new PointHistoryItem(
                        rs.getLong("id"),
                        rs.getInt("amount"),
                        PointReason.valueOf(rs.getString("reason")),
                        PointFund.valueOf(rs.getString("fund")),
                        rs.getInt("paid_amount"),
                        rs.getString("label"),
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** 그 제출의 측정 조각 날짜. 직접 입력한 기록은 공부 포인트를 받지 않는다. */
    public List<LocalDate> measuredDatesOf(long userId, Instant submissionStartedAt) {
        return jdbc.sql("""
                        SELECT DISTINCT stat_date FROM study_session
                        WHERE user_id = :u AND submission_started_at = :s AND source = 'MEASURED'
                        ORDER BY stat_date""")
                .param("u", userId)
                .param("s", Timestamp.from(submissionStartedAt))
                .query(LocalDate.class)
                .list();
    }

    /** 그 제출의 마지막 조각 날짜. 제출이 없으면 null. */
    public LocalDate lastDateOf(long userId, Instant submissionStartedAt) {
        return jdbc.sql("SELECT MAX(stat_date) FROM study_session WHERE user_id = :u AND submission_started_at = :s")
                .param("u", userId)
                .param("s", Timestamp.from(submissionStartedAt))
                .query(LocalDate.class)
                .optional()
                .orElse(null);
    }

    public long measuredFocusSecOn(long userId, LocalDate date) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(focus_sec), 0) FROM study_session
                        WHERE user_id = :u AND stat_date = :d AND source = 'MEASURED'""")
                .param("u", userId)
                .param("d", date)
                .query(Long.class)
                .single();
    }

    public int studyGrantedOn(long userId, LocalDate date) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM point_ledger
                        WHERE user_id = :u AND reason IN ('STUDY', 'STUDY_ADJUST') AND stat_date = :d""")
                .param("u", userId)
                .param("d", date)
                .query(Integer.class)
                .single();
    }

    /** DB에 저장된 그 제출의 시작 시각. DB는 마이크로초까지만 담아 요청 값과 다를 수 있어, 참조 키는 이 값으로 만든다. */
    public Optional<Instant> storedSubmissionStart(long userId, Instant submissionStartedAt) {
        return jdbc.sql("""
                        SELECT submission_started_at FROM study_session
                        WHERE user_id = :u AND submission_started_at = :s LIMIT 1""")
                .param("u", userId)
                .param("s", Timestamp.from(submissionStartedAt))
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .optional();
    }

    /** 그 제출이 공부 포인트를 받은 날짜별 금액(날짜 옮김 포함). 0이 된 날짜는 뺀다. */
    public Map<LocalDate, Integer> studyGrantedByDate(long userId, String refKeyPrefix) {
        Map<LocalDate, Integer> byDate = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT stat_date, SUM(amount) AS total FROM point_ledger
                        WHERE user_id = :u AND reason IN ('STUDY', 'STUDY_ADJUST') AND ref_key LIKE :prefix
                        GROUP BY stat_date HAVING SUM(amount) <> 0 ORDER BY stat_date""")
                .param("u", userId)
                .param("prefix", refKeyPrefix + "%")
                .query((rs, n) -> byDate.put(rs.getObject("stat_date", LocalDate.class), rs.getInt("total")))
                .list();
        return byDate;
    }

    /** 그날의 무료 포인트(공부·출석·하루 목표·광고) 합계. 메달은 날짜가 없어 들지 않는다. */
    public int earnedOn(long userId, LocalDate date) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM point_ledger
                        WHERE user_id = :u AND stat_date = :d AND reason IN ('STUDY', 'STUDY_ADJUST', 'ATTENDANCE', 'DAILY_GOAL', 'AD')""")
                .param("u", userId)
                .param("d", date)
                .query(Integer.class)
                .single();
    }

    /** 그날 그 사유로 받은 횟수. */
    public int countOn(long userId, PointReason reason, LocalDate date) {
        return jdbc.sql("SELECT COUNT(*) FROM point_ledger WHERE user_id = :u AND reason = :r AND stat_date = :d")
                .param("u", userId)
                .param("r", reason.name())
                .param("d", date)
                .query(Integer.class)
                .single();
    }

    public boolean exists(long userId, PointReason reason, String refKey) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM point_ledger WHERE user_id = :u AND reason = :r AND ref_key = :k)")
                .param("u", userId)
                .param("r", reason.name())
                .param("k", refKey)
                .query(Boolean.class)
                .single();
    }

    /** 아직 포인트를 안 받은 내 메달. 최근 마감 순. from보다 앞선 기간의 메달은 뺀다. */
    public List<MedalRow> unclaimedMedals(long userId, LocalDate from) {
        return jdbc.sql("""
                        SELECT r.id, r.board_key, r.period_start, r.rank FROM ranking_record r
                        WHERE r.user_id = :u AND r.period_start >= :from
                          AND NOT EXISTS (
                              SELECT 1 FROM point_ledger l
                              WHERE l.user_id = r.user_id AND l.reason = 'MEDAL' AND l.ref_key = 'record:' || r.id)
                        ORDER BY r.closes_at DESC, r.id""")
                .param("u", userId)
                .param("from", from)
                .query(PointRepository::medal)
                .list();
    }

    public Optional<MedalRow> findMedal(long userId, long recordId) {
        return jdbc.sql("SELECT id, board_key, period_start, rank FROM ranking_record WHERE id = :id AND user_id = :u")
                .param("id", recordId)
                .param("u", userId)
                .query(PointRepository::medal)
                .optional();
    }

    private static MedalRow medal(ResultSet rs, int rowNum) throws SQLException {
        return new MedalRow(
                rs.getLong("id"),
                rs.getString("board_key"),
                rs.getObject("period_start", LocalDate.class),
                rs.getInt("rank"));
    }

    public int studyGrantedFor(long userId, String refKeyPrefix) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM point_ledger
                        WHERE user_id = :u AND reason IN ('STUDY', 'STUDY_ADJUST') AND ref_key LIKE :prefix""")
                .param("u", userId)
                .param("prefix", refKeyPrefix + "%")
                .query(Integer.class)
                .single();
    }
}
