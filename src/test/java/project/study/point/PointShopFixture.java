package project.study.point;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 포인트·상점·마스코트 테스트 데이터. 공통 계정 병합·탈퇴 fixture도 새 사용자 참조 컬럼을 빠뜨리지 않게 이것을 부른다. */
@Component
@RequiredArgsConstructor
public class PointShopFixture {

    private final JdbcClient jdbc;

    /** 소셜 로그인한 사용자. */
    public long member() {
        return user("GOOGLE");
    }

    /** 비회원. */
    public long guest() {
        return user("DEVICE");
    }

    private long user(String provider) {
        return jdbc.sql("INSERT INTO users (provider, provider_user_id, nickname) VALUES (:p, :id, :n) RETURNING id")
                .param("p", provider)
                .param("id", UUID.randomUUID().toString())
                .param("n", "point-" + UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    public void givePoints(long userId, int amount) {
        jdbc.sql("""
                INSERT INTO point_ledger (user_id, amount, reason, ref_key) VALUES (:u, :a, 'STUDY', :ref)""")
                .param("u", userId)
                .param("a", amount)
                .param("ref", "fixture-" + UUID.randomUUID())
                .update();
        jdbc.sql("""
                INSERT INTO point_account (user_id, balance) VALUES (:u, :a)
                ON CONFLICT (user_id) DO UPDATE SET balance = point_account.balance + EXCLUDED.balance""").param("u", userId).param("a", amount).update();
    }

    /** 포인트로 사는 상품. accessoryOf가 null이면 타이머 배경, 아니면 그 마스코트의 머리 소품이다. */
    public long pricedItem(String accessoryOf, int price) {
        return jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name, price)
                        VALUES (:code, :category, :mascot, :slot, '상품', :price) RETURNING id""")
                .param("code", "ITEM_" + UUID.randomUUID())
                .param("category", accessoryOf == null ? "TIMER_BACKGROUND" : "MASCOT_ACCESSORY")
                .param("mascot", accessoryOf)
                .param("slot", accessoryOf == null ? null : "HEAD")
                .param("price", price)
                .query(Long.class)
                .single();
    }

    /** 조건으로 받는 보상 타이머 배경. */
    public long rewardItem(String unlockType, int unlockValue) {
        return jdbc.sql("""
                        INSERT INTO shop_item (code, category, name, unlock_type, unlock_value)
                        VALUES (:code, 'TIMER_BACKGROUND', '보상', :type, :value) RETURNING id""")
                .param("code", "ITEM_" + UUID.randomUUID())
                .param("type", unlockType)
                .param("value", unlockValue)
                .query(Long.class)
                .single();
    }

    /** 측정한 세션 한 조각을 바로 넣는다. day는 'YYYY-MM-DD', 시작은 그날 UTC 01시에서 hourOffset시간 뒤다. */
    public void measuredSession(long userId, String day, int hourOffset, int focusSec) {
        jdbc.sql("""
                INSERT INTO study_session (user_id, stat_date, started_at, submission_started_at, ended_at, study_sec, focus_sec, source)
                VALUES (:u, CAST(:day AS date), CAST(:day AS timestamptz) + make_interval(hours => :h),
                        CAST(:day AS timestamptz) + make_interval(hours => :h),
                        CAST(:day AS timestamptz) + make_interval(hours => :h, secs => :focus), :focus, :focus, 'MEASURED')""")
                .param("u", userId)
                .param("day", day)
                .param("h", hourOffset)
                .param("focus", focusSec)
                .update();
    }

    /** 사용자 참조 컬럼이 있는 일곱 테이블에 한 행씩 넣는다. */
    public void seedEverything(long userId) {
        givePoints(userId, 100);
        long item = pricedItem("BOOK", 10);
        jdbc.sql("INSERT INTO user_item (user_id, item_id) VALUES (:u, :i)")
                .param("u", userId)
                .param("i", item)
                .update();
        jdbc.sql(
                        "INSERT INTO user_mascot_accessory (user_id, mascot_code, slot, item_id) VALUES (:u, 'BOOK', 'HEAD', :i)")
                .param("u", userId)
                .param("i", item)
                .update();
        jdbc.sql("INSERT INTO user_decoration (user_id) VALUES (:u)")
                .param("u", userId)
                .update();
        jdbc.sql("INSERT INTO user_mascot (user_id, selected_mascot_code) VALUES (:u, 'BOOK')")
                .param("u", userId)
                .update();
        jdbc.sql("INSERT INTO user_mascot_seen (user_id, mascot_code) VALUES (:u, 'BOOK')")
                .param("u", userId)
                .update();
    }
}
