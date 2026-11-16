package project.study.shop;

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
import project.study.point.PointShopFixture;
import project.study.shop.dto.ShopItemResponse;
import project.study.shop.service.ShopService;

/** 스토어 결제로 사는 상점 상품. RevenueCat 대신 테스트가 알림을 보낸다. */
@SpringBootTest(properties = "app.points.charge.revenuecat-webhook-auth=test-webhook-auth")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaidItemApiTest {

    private static final String AUTH = "test-webhook-auth";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    ShopService shopService;

    @Test
    void 결제_알림이_오면_상품을_갖고_같은_알림이_다시_와도_한_번만_처리한다() {
        long user = fixture.member();
        String product = productId();
        long item = paidAccessory(product);
        String tx = tx();

        assertThat(item(user, item).owned()).isFalse();
        assertThat(item(user, item).storeProductId()).isEqualTo(product);
        assertThat(item(user, item).price()).isNull();

        assertThat(webhook(AUTH, purchase(user, product, tx))).hasStatusOk();
        assertThat(webhook(AUTH, purchase(user, product, tx))).hasStatusOk();

        assertThat(item(user, item).owned()).isTrue();
        assertThat(purchaseRows(tx)).isEqualTo(1);
        assertThat(balanceOf(user)).isZero();
    }

    @Test
    void 결제_상품은_포인트로_살_수_없고_Authorization이_다르면_주지_않는다() {
        long user = fixture.member();
        fixture.givePoints(user, 10_000);
        String product = productId();
        long item = paidAccessory(product);

        assertThat(mvc.post()
                        .uri("/api/shop/items/{id}/purchase", item)
                        .with(asUser(user))
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("ITEM_NOT_PURCHASABLE"));
        assertThat(webhook("wrong", purchase(user, product, tx()))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(item(user, item).owned()).isFalse();
    }

    @Test
    void 환불되면_보유를_거두고_끼고_있던_소품도_바로_벗긴다() {
        long user = fixture.member();
        String product = productId();
        long item = paidAccessory(product);
        String tx = tx();
        webhook(AUTH, purchase(user, product, tx));
        assertThat(mvc.put()
                        .uri("/api/decorations/mascots/BOOK/accessories")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot\":\"HEAD\",\"itemId\":" + item + "}")
                        .exchange())
                .hasStatusOk();
        assertThat(item(user, item).inUse()).isTrue();

        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();
        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();

        assertThat(item(user, item).owned()).isFalse();
        assertThat(item(user, item).inUse()).isFalse();
        assertThat(status(tx)).isEqualTo("REFUNDED");
        // 환불된 결제의 알림이 다시 와도 돌려주지 않는다
        webhook(AUTH, purchase(user, product, tx));
        assertThat(item(user, item).owned()).isFalse();
    }

    @Test
    void 환불_알림이_먼저_오면_뒤에_온_결제는_주지_않고_다른_결제가_살아_있으면_보유는_남는다() {
        long user = fixture.member();
        String product = productId();
        long item = paidAccessory(product);
        String early = tx();
        webhook(AUTH, refund(user, product, early));
        webhook(AUTH, purchase(user, product, early));
        assertThat(item(user, item).owned()).isFalse();

        String first = tx();
        String second = tx();
        webhook(AUTH, purchase(user, product, first));
        webhook(AUTH, purchase(user, product, second));
        webhook(AUTH, refund(user, product, first));
        assertThat(item(user, item).owned()).isTrue();
        webhook(AUTH, refund(user, product, second));
        assertThat(item(user, item).owned()).isFalse();
    }

    @Test
    void 산_뒤에_스토어_상품_ID를_바꿔도_환불되면_산_상품을_거둔다() {
        long user = fixture.member();
        String product = productId();
        long item = paidAccessory(product);
        String tx = tx();
        webhook(AUTH, purchase(user, product, tx));
        jdbc.sql("UPDATE shop_item SET store_product_id = :p WHERE id = :id")
                .param("p", productId())
                .param("id", item)
                .update();

        assertThat(webhook(AUTH, refund(user, product, tx))).hasStatusOk();

        assertThat(item(user, item).owned()).isFalse();
        assertThat(status(tx)).isEqualTo("REFUNDED");
    }

    @Test
    void 비회원의_결제_알림은_주지_않는다() {
        long guest = fixture.guest();
        String product = productId();
        long item = paidAccessory(product);

        assertThat(webhook(AUTH, purchase(guest, product, tx()))).hasStatusOk();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_item WHERE user_id = :u AND item_id = :i")
                        .param("u", guest)
                        .param("i", item)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void 회원번호를_사용자_ID로_보낸_알림은_주지_않는다() {
        long user = fixture.member();
        String product = productId();
        long item = paidAccessory(product);
        String body = purchase(user, product, tx()).replace(fixture.purchaseId(user), String.valueOf(user));

        assertThat(webhook(AUTH, body)).hasStatusOk();

        assertThat(item(user, item).owned()).isFalse();
    }

    private long paidAccessory(String storeProductId) {
        return jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name, grade, store_product_id)
                        VALUES (:code, 'MASCOT_ACCESSORY', 'BOOK', 'HEAD', '결제 상품', 3, :p) RETURNING id""")
                .param("code", "PAID_" + UUID.randomUUID())
                .param("p", storeProductId)
                .query(Long.class)
                .single();
    }

    private ShopItemResponse item(long user, long itemId) {
        return shopService.list(user, "ko").items().stream()
                .filter(item -> item.id() == itemId)
                .findFirst()
                .orElseThrow();
    }

    private static String productId() {
        return "item_" + UUID.randomUUID();
    }

    private static String tx() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String purchase(long user, String product, String tx) {
        return """
                {"api_version":"1.0","event":{"type":"NON_RENEWING_PURCHASE","id":"%s","app_user_id":"%s",
                 "product_id":"%s","transaction_id":"%s","store":"APP_STORE","environment":"PRODUCTION",
                 "purchased_at_ms":1762900000000,"price":3.0}}
                """.formatted(UUID.randomUUID(), fixture.purchaseId(user), product, tx);
    }

    private String refund(long user, String product, String tx) {
        return """
                {"api_version":"1.0","event":{"type":"CANCELLATION","id":"%s","app_user_id":"%s",
                 "product_id":"%s","transaction_id":"%s","cancel_reason":"CUSTOMER_SUPPORT","store":"APP_STORE",
                 "environment":"PRODUCTION","price":-3.0}}
                """.formatted(UUID.randomUUID(), fixture.purchaseId(user), product, tx);
    }

    private MvcTestResult webhook(String authorization, String body) {
        return mvc.post()
                .uri("/api/points/purchases/revenuecat/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", authorization)
                .exchange();
    }

    private int purchaseRows(String tx) {
        return jdbc.sql("SELECT COUNT(*) FROM point_purchase WHERE transaction_id = :t")
                .param("t", tx)
                .query(Integer.class)
                .single();
    }

    private String status(String tx) {
        return jdbc.sql("SELECT status FROM point_purchase WHERE transaction_id = :t")
                .param("t", tx)
                .query(String.class)
                .single();
    }

    private int balanceOf(long user) {
        return jdbc.sql("SELECT COALESCE((SELECT balance FROM point_account WHERE user_id = :u), 0)")
                .param("u", user)
                .query(Integer.class)
                .single();
    }
}
