package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/** 약관 환불 규칙용 결제 기록 (BY-1304). RevenueCat 대신 테스트가 알림을 보낸다. */
@SpringBootTest(properties = "app.points.charge.revenuecat-webhook-auth=test-webhook-auth")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaidLotApiTest {

    private static final String AUTH = "test-webhook-auth";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Test
    void 무료_포인트를_먼저_쓰고_유료는_먼저_충전한_건부터_쓴다() {
        long user = fixture.member();
        fixture.givePoints(user, 100);
        String first = tx();
        String second = tx();
        charge(user, product(100), first, 1_762_900_000_000L);
        charge(user, product(200), second, 1_762_900_100_000L);

        buy(user, fixture.pricedItem(null, 150));

        // 무료 100을 먼저 쓰고 유료 50은 먼저 충전한 건에서 나간다
        assertThat(remaining(first)).isEqualTo(50);
        assertThat(remaining(second)).isEqualTo(200);
        buy(user, fixture.pricedItem(null, 100));
        assertThat(remaining(first)).isZero();
        assertThat(remaining(second)).isEqualTo(150);
        assertLotsMatchPaidBalance(user);
    }

    @Test
    void 스토어_환불은_그_건의_남은_양부터_지우고_이미_쓴_몫은_다른_건으로_갚는다() {
        long user = fixture.member();
        String first = tx();
        String second = tx();
        String product = product(100);
        charge(user, product, first, 1_762_900_000_000L);
        charge(user, product, second, 1_762_900_100_000L);
        buy(user, fixture.pricedItem(null, 100));
        assertThat(remaining(first)).isZero();

        // 다 쓴 첫 건이 환불되면 남은 둘째 건의 유료 포인트로 갚는다
        refund(user, product, first);

        assertThat(remaining(first)).isZero();
        assertThat(remaining(second)).isZero();
        assertLotsMatchPaidBalance(user);
    }

    @Test
    void 환불로_생긴_빚은_무료_포인트부터_갚아_쓰지_않은_건은_남는다() {
        long user = fixture.member();
        String spent = tx();
        String untouched = tx();
        String product = product(100);
        charge(user, product, spent, 1_762_900_000_000L);
        buy(user, fixture.pricedItem(null, 70));
        charge(user, product(50), untouched, 1_762_900_100_000L);
        fixture.givePoints(user, 40);

        // 첫 건(70 씀, 30 남음)을 환불: 남은 30은 사라지고 쓴 70은 무료 40, 다음 건 30으로 갚는다
        refund(user, product, spent);

        assertThat(remaining(spent)).isZero();
        assertThat(remaining(untouched)).isEqualTo(20);
        assertThat(balanceOf(user)).isEqualTo(20);
        assertLotsMatchPaidBalance(user);
    }

    @Test
    void 잔액이_음수일_때_충전하면_갚고_남은_만큼만_그_건의_유료_포인트다() {
        long user = fixture.member();
        String first = tx();
        String product = product(100);
        charge(user, product, first, 1_762_900_000_000L);
        buy(user, fixture.pricedItem(null, 100));
        refund(user, product, first);
        assertThat(balanceOf(user)).isEqualTo(-100);

        String second = tx();
        charge(user, product(300), second, 1_762_900_100_000L);

        assertThat(remaining(second)).isEqualTo(200);
        assertThat(balanceOf(user)).isEqualTo(200);
        assertLotsMatchPaidBalance(user);
    }

    @Test
    void 계정을_삭제해도_충전_건별_남은_유료_포인트와_삭제_시각이_남는다() {
        long user = fixture.member();
        String tx = tx();
        charge(user, product(500), tx, 1_762_900_000_000L);
        buy(user, fixture.pricedItem(null, 120));

        assertThat(mvc.delete()
                        .uri("/api/users/me")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user)))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.sql("""
                        SELECT user_id IS NULL AND owner_deleted_at IS NOT NULL AND paid_remaining = 380
                        FROM point_purchase WHERE transaction_id = :t""").param("t", tx).query(Boolean.class).single()).isTrue();

        // 삭제 뒤 스토어가 환불하면 남겨 둔 양도 지운다
        webhook("""
                {"api_version":"1.0","event":{"type":"CANCELLATION","id":"%s","app_user_id":"x",
                 "product_id":"%s","transaction_id":"%s","cancel_reason":"CUSTOMER_SUPPORT",
                 "store":"APP_STORE","environment":"PRODUCTION"}}
                """.formatted(UUID.randomUUID(), productOf(tx), tx));
        assertThat(remaining(tx)).isZero();
    }

    private String productOf(String tx) {
        return jdbc.sql("SELECT store_product_id FROM point_purchase WHERE transaction_id = :t")
                .param("t", tx)
                .query(String.class)
                .single();
    }

    @Test
    void 결제로_산_상품을_처음_입은_때를_적고_벗었다_다시_입어도_바뀌지_않는다() {
        long user = fixture.member();
        String storeProduct = "item_" + UUID.randomUUID();
        long item = jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name, store_product_id)
                        VALUES (:code, 'MASCOT_ACCESSORY', 'STOPWATCH', 'HEAD', '결제 상품', :p) RETURNING id""")
                .param("code", "PAID_" + UUID.randomUUID())
                .param("p", storeProduct)
                .query(Long.class)
                .single();
        String tx = tx();
        charge(user, storeProduct, tx, 1_762_900_000_000L);
        assertThat(wornAt(tx)).isNull();

        wear(user, item);
        String worn = wornAt(tx);
        wear(user, null);
        wear(user, item);

        assertThat(worn).isNotNull();
        assertThat(wornAt(tx)).isEqualTo(worn);
    }

    private void assertLotsMatchPaidBalance(long user) {
        List<Integer> sums = jdbc.sql("""
                        SELECT (SELECT COALESCE(SUM(paid_remaining), 0) FROM point_purchase
                                WHERE user_id = :u AND status = 'GRANTED')
                        UNION ALL
                        SELECT COALESCE((SELECT paid_balance FROM point_account WHERE user_id = :u), 0)""").param("u", user).query(Integer.class).list();
        assertThat(sums.get(0)).isEqualTo(sums.get(1));
    }

    private void buy(long user, long itemId) {
        assertThat(mvc.post()
                        .uri("/api/shop/items/{id}/purchase", itemId)
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user)))
                .hasStatusOk();
    }

    private void wear(long user, Long itemId) {
        assertThat(mvc.put()
                        .uri("/api/decorations/mascots/STOPWATCH/accessories")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\":\"HEAD\",\"itemId\":" + itemId + "}"))
                .hasStatusOk();
    }

    private void charge(long user, String product, String tx, long purchasedAtMs) {
        webhook("""
                {"api_version":"1.0","event":{"type":"NON_RENEWING_PURCHASE","id":"%s","app_user_id":"%s",
                 "product_id":"%s","transaction_id":"%s","store":"APP_STORE","environment":"PRODUCTION",
                 "purchased_at_ms":%d}}
                """.formatted(UUID.randomUUID(), fixture.purchaseId(user), product, tx, purchasedAtMs));
    }

    private void refund(long user, String product, String tx) {
        webhook("""
                {"api_version":"1.0","event":{"type":"CANCELLATION","id":"%s","app_user_id":"%s",
                 "product_id":"%s","transaction_id":"%s","cancel_reason":"CUSTOMER_SUPPORT","store":"APP_STORE",
                 "environment":"PRODUCTION"}}
                """.formatted(UUID.randomUUID(), fixture.purchaseId(user), product, tx));
    }

    private void webhook(String body) {
        assertThat(mvc.post()
                        .uri("/api/points/purchases/revenuecat/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", AUTH))
                .hasStatusOk();
    }

    private String product(int points) {
        String storeProductId = "points_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO point_product (store_product_id, points, bonus_points) VALUES (:p, :points, 0)")
                .param("p", storeProductId)
                .param("points", points)
                .update();
        return storeProductId;
    }

    private int remaining(String tx) {
        return jdbc.sql("SELECT paid_remaining FROM point_purchase WHERE transaction_id = :t")
                .param("t", tx)
                .query(Integer.class)
                .single();
    }

    private String wornAt(String tx) {
        return jdbc.sql("SELECT CAST(item_first_worn_at AS text) FROM point_purchase WHERE transaction_id = :t")
                .param("t", tx)
                .query((rs, n) -> rs.getString(1))
                .list()
                .getFirst();
    }

    private int balanceOf(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    private static String tx() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
