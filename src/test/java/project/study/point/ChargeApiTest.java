package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.user.account.UserDataHandler;

/** 포인트 충전과 환불. RevenueCat 대신 테스트가 알림을 보낸다. 알림은 우리 토큰도 API-Version 헤더도 없이 온다. */
@SpringBootTest(properties = "app.points.charge.revenuecat-webhook-auth=test-webhook-auth")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChargeApiTest {

    private static final String AUTH = "test-webhook-auth";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    java.util.List<UserDataHandler> handlers;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void 결제_알림이_오면_결제_몫과_덤을_주고_같은_결제는_한_번만_준다() {
        long user = fixture.member();
        String product = product(1000, 100);
        String tx = tx();

        assertThat(webhook(AUTH, purchase(user, product, tx, "PRODUCTION"))).hasStatusOk();
        // RevenueCat은 같은 알림을 다시 보낼 수 있다
        assertThat(webhook(AUTH, purchase(user, product, tx, "PRODUCTION"))).hasStatusOk();

        assertThat(balance(user))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(1100))
                .hasPathSatisfying("$.paidBalance", v -> assertThat(v).isEqualTo(1000));
        assertThat(mvc.get()
                        .uri("/api/points/history")
                        .with(asUser(user))
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .bodyJson()
                .hasPathSatisfying("$.items.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.items[0].reason", v -> assertThat(v).isEqualTo("CHARGE"))
                .hasPathSatisfying("$.items[0].fund", v -> assertThat(v).isEqualTo("PAID"))
                .hasPathSatisfying("$.items[0].amount", v -> assertThat(v).isEqualTo(1100))
                .hasPathSatisfying("$.items[0].paidAmount", v -> assertThat(v).isEqualTo(1000))
                .hasPathSatisfying("$.items[0].label", v -> assertThat(v).isEqualTo(product));
    }

    @Test
    void Authorization_값이_다르거나_없으면_401이고_주지_않는다() {
        long user = fixture.member();
        String product = product(1000, 0);

        assertThat(webhook("wrong", purchase(user, product, tx(), "PRODUCTION")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(webhook(null, purchase(user, product, tx(), "PRODUCTION"))).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void 샌드박스_결제는_기본으로_받지_않는다() {
        long user = fixture.member();

        assertThat(webhook(AUTH, purchase(user, product(1000, 0), tx(), "SANDBOX")))
                .hasStatusOk();

        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void 쓸_때는_무료_포인트부터_빠지고_모자란_만큼만_결제_포인트에서_빠진다() {
        long user = fixture.member();
        fixture.givePoints(user, 100);
        webhook(AUTH, purchase(user, product(200, 0), tx(), "PRODUCTION"));
        long item = fixture.pricedItem(null, 150);

        assertThat(mvc.post()
                        .uri("/api/shop/items/{id}/purchase", item)
                        .with(asUser(user))
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatusOk();

        assertThat(balance(user))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(150))
                .hasPathSatisfying("$.paidBalance", v -> assertThat(v).isEqualTo(150));
        assertThat(jdbc.sql("SELECT paid_amount FROM point_ledger WHERE user_id = :u AND reason = 'PURCHASE'")
                        .param("u", user)
                        .query(Integer.class)
                        .single())
                .isEqualTo(-50);
    }

    @Test
    void 환불_알림이_오면_그_결제의_포인트를_되돌리고_다시_와도_한_번만_되돌린다() {
        long user = fixture.member();
        fixture.givePoints(user, 30);
        String product = product(1000, 100);
        String tx = tx();
        webhook(AUTH, purchase(user, product, tx, "PRODUCTION"));

        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();
        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();

        assertThat(balance(user))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(30))
                .hasPathSatisfying("$.paidBalance", v -> assertThat(v).isEqualTo(0));
        assertThat(purchaseRow(tx)).isEqualTo("REFUNDED/1100/0");
    }

    @Test
    void 이미_쓴_포인트는_남은_만큼만_0까지_되돌리고_모자란_만큼을_적어_둔다() {
        long user = fixture.member();
        String product = product(1000, 0);
        String tx = tx();
        webhook(AUTH, purchase(user, product, tx, "PRODUCTION"));
        long item = fixture.pricedItem(null, 700);
        mvc.post()
                .uri("/api/shop/items/{id}/purchase", item)
                .with(asUser(user))
                .header(ApiVersionConfig.HEADER, "1")
                .exchange();

        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();

        assertThat(balance(user))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(0))
                .hasPathSatisfying("$.paidBalance", v -> assertThat(v).isEqualTo(0));
        assertThat(purchaseRow(tx)).isEqualTo("REFUNDED/300/700");
        // 산 상품은 그대로 가진다
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_item WHERE user_id = :u AND item_id = :i")
                        .param("u", user)
                        .param("i", item)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void 다른_결제로_산_포인트가_남아_있으면_환불은_그_결제_몫까지만_결제_몫을_줄인다() {
        long user = fixture.member();
        String product = product(500, 0);
        String refunded = tx();
        webhook(AUTH, purchase(user, product, refunded, "PRODUCTION"));
        webhook(AUTH, purchase(user, product, tx(), "PRODUCTION"));

        webhook(AUTH, refund(user, product, refunded));

        assertThat(balance(user))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(500))
                .hasPathSatisfying("$.paidBalance", v -> assertThat(v).isEqualTo(500));
    }

    @Test
    void 환불_알림이_결제_알림보다_먼저_오면_뒤에_온_결제는_주지_않는다() {
        long user = fixture.member();
        String product = product(1000, 0);
        String tx = tx();

        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();
        assertThat(webhook(AUTH, purchase(user, product, tx, "PRODUCTION"))).hasStatusOk();

        assertThat(balanceOf(user)).isZero();
        assertThat(purchaseRow(tx)).isEqualTo("REFUNDED/0/0");
    }

    @Test
    void 구독_해지_같은_다른_취소와_다른_종류의_알림은_아무것도_하지_않는다() {
        long user = fixture.member();
        String product = product(1000, 0);
        String tx = tx();
        webhook(AUTH, purchase(user, product, tx, "PRODUCTION"));

        assertThat(webhook(AUTH, refund(user, product, tx).replace("CUSTOMER_SUPPORT", "UNSUBSCRIBE")))
                .hasStatusOk();
        assertThat(webhook(AUTH, "{\"api_version\":\"1.0\",\"event\":{\"type\":\"TEST\",\"id\":\"x\"}}"))
                .hasStatusOk();
        assertThat(webhook(AUTH, "{\"api_version\":\"1.0\"}")).hasStatusOk();

        assertThat(balanceOf(user)).isEqualTo(1000);
    }

    @Test
    void 로그인_전_익명_ID로_온_알림은_다른_이름들에서_회원을_찾고_여러_개를_산_결제는_개수만큼_준다() {
        long user = fixture.member();
        String product = product(100, 10);
        String body = """
                {"api_version":"1.0","event":{"type":"NON_RENEWING_PURCHASE","id":"%s",
                 "app_user_id":"$RCAnonymousID:abc","original_app_user_id":"$RCAnonymousID:abc",
                 "aliases":["$RCAnonymousID:abc","%d"],"product_id":"%s","transaction_id":"%s",
                 "store":"PLAY_STORE","environment":"PRODUCTION","quantity":3,"purchased_at_ms":1762900000000}}
                """.formatted(UUID.randomUUID(), user, product, tx());

        assertThat(webhook(AUTH, body)).hasStatusOk();

        assertThat(balance(user))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(330))
                .hasPathSatisfying("$.paidBalance", v -> assertThat(v).isEqualTo(300));
    }

    @Test
    void 알림의_다른_이름들에_회원이_여럿이면_누구의_결제인지_몰라_주지_않고_로그인한_ID가_회원이면_그_사람에게_준다() {
        long first = fixture.member();
        long second = fixture.member();
        String product = product(100, 0);
        String ambiguous = """
                {"api_version":"1.0","event":{"type":"NON_RENEWING_PURCHASE","id":"x",
                 "app_user_id":"$RCAnonymousID:abc","aliases":["%d","%d"],"product_id":"%s",
                 "transaction_id":"%s","store":"APP_STORE","environment":"PRODUCTION"}}
                """.formatted(first, second, product, tx());
        String loggedIn = """
                {"api_version":"1.0","event":{"type":"NON_RENEWING_PURCHASE","id":"y",
                 "app_user_id":"%d","aliases":["%d","%d"],"product_id":"%s",
                 "transaction_id":"%s","store":"APP_STORE","environment":"PRODUCTION"}}
                """.formatted(second, first, second, product, tx());

        assertThat(webhook(AUTH, ambiguous)).hasStatusOk();
        assertThat(balanceOf(first)).isZero();
        assertThat(balanceOf(second)).isZero();

        assertThat(webhook(AUTH, loggedIn)).hasStatusOk();
        assertThat(balanceOf(first)).isZero();
        assertThat(balanceOf(second)).isEqualTo(100);
    }

    @Test
    void 같은_결제의_지급과_환불이_동시에_와도_포인트가_남지_않는다() throws Exception {
        String product = product(1000, 0);
        for (int round = 0; round < 8; round++) {
            long user = fixture.member();
            String tx = tx();
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var start = new java.util.concurrent.CountDownLatch(1);
                var grant = executor.submit(() -> {
                    start.await();
                    return webhook(AUTH, purchase(user, product, tx, "PRODUCTION"));
                });
                var refund = executor.submit(() -> {
                    start.await();
                    return webhook(AUTH, refund(user, product, tx));
                });
                start.countDown();
                assertThat(grant.get()).hasStatusOk();
                assertThat(refund.get()).hasStatusOk();
            }
            assertThat(balanceOf(user)).isZero();
            assertThat(purchaseRow(tx)).startsWith("REFUNDED/");
        }
    }

    @Test
    void 비회원과_없는_상품의_결제_알림은_주지_않고_200이다() {
        long guest = fixture.guest();
        long user = fixture.member();

        assertThat(webhook(AUTH, purchase(guest, product(1000, 0), tx(), "PRODUCTION")))
                .hasStatusOk();
        assertThat(webhook(AUTH, purchase(user, "no-such-product", tx(), "PRODUCTION")))
                .hasStatusOk();

        assertThat(balanceOf(guest)).isZero();
        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void 탈퇴해도_결제_기록은_남아_같은_결제가_다시_지급되지_않는다() {
        long user = fixture.member();
        String product = product(1000, 0);
        String tx = tx();
        webhook(AUTH, purchase(user, product, tx, "PRODUCTION"));

        transactions.executeWithoutResult(status -> handlers.stream()
                .filter(handler -> handler.columns().contains("point_purchase.user_id"))
                .forEach(handler -> handler.erase(user)));

        assertThat(purchaseRow(tx)).isEqualTo("GRANTED/0/0");
        assertThat(webhook(AUTH, purchase(user, product, tx, "PRODUCTION"))).hasStatusOk();
        assertThat(balanceOf(user)).isZero();
        // 탈퇴한 사용자의 결제가 환불돼도 되돌릴 잔액이 없을 뿐 실패하지 않는다
        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();
        assertThat(purchaseRow(tx)).isEqualTo("REFUNDED/0/1000");
    }

    private String product(int points, int bonus) {
        String storeProductId = "points_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO point_product (store_product_id, points, bonus_points) VALUES (:p, :points, :bonus)")
                .param("p", storeProductId)
                .param("points", points)
                .param("bonus", bonus)
                .update();
        return storeProductId;
    }

    private static String tx() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String purchase(long user, String product, String tx, String environment) {
        return """
                {"api_version":"1.0","event":{"type":"NON_RENEWING_PURCHASE","id":"%s","app_user_id":"%d",
                 "original_app_user_id":"$RCAnonymousID:abc","aliases":["$RCAnonymousID:abc"],
                 "product_id":"%s","transaction_id":"%s","original_transaction_id":"%s","store":"APP_STORE",
                 "environment":"%s","purchased_at_ms":1762900000000,"expiration_at_ms":null,"price":1.5}}
                """.formatted(UUID.randomUUID(), user, product, tx, tx, environment);
    }

    private static String refund(long user, String product, String tx) {
        return """
                {"api_version":"1.0","event":{"type":"CANCELLATION","id":"%s","app_user_id":"%d",
                 "product_id":"%s","transaction_id":"%s","cancel_reason":"CUSTOMER_SUPPORT","store":"APP_STORE",
                 "environment":"PRODUCTION","price":-1.5}}
                """.formatted(UUID.randomUUID(), user, product, tx);
    }

    private MvcTestResult webhook(String authorization, String body) {
        var request = mvc.post()
                .uri("/api/points/purchases/revenuecat/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return (authorization == null ? request : request.header("Authorization", authorization)).exchange();
    }

    private MvcTestResult balance(long user) {
        return mvc.get()
                .uri("/api/points")
                .with(asUser(user))
                .header(ApiVersionConfig.HEADER, "1")
                .exchange();
    }

    private int balanceOf(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    /** "상태/되돌린 포인트/모자란 포인트". */
    private String purchaseRow(String tx) {
        return jdbc.sql("""
                        SELECT status || '/' || reclaimed_points || '/' || shortfall_points FROM point_purchase
                        WHERE transaction_id = :t""").param("t", tx).query(String.class).single();
    }
}
