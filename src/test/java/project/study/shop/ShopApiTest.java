package project.study.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import project.study.common.exception.ConflictException;
import project.study.config.ApiVersionConfig;
import project.study.point.PointShopFixture;
import project.study.point.dto.PointHistoryItem;
import project.study.point.dto.PointHistoryResponse;
import project.study.point.dto.PointHistoryType;
import project.study.point.service.PointService;
import project.study.shop.dto.PurchaseResponse;
import project.study.shop.dto.ShopItemResponse;
import project.study.shop.service.ShopService;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShopApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    ShopService shopService;

    @Autowired
    PointService pointService;

    @Test
    void 사면_잔액이_줄고_같은_구매가_다시_오면_이미_가짐으로_거절한다() {
        long user = fixture.member();
        fixture.givePoints(user, 1000);
        long item = fixture.pricedItem(null, 400);

        assertThat(purchase(user, item))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(600));
        assertCode(purchase(user, item), HttpStatus.CONFLICT, "ITEM_ALREADY_OWNED");

        assertThat(get(user, "/api/points"))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(600));
        assertThat(get(user, "/api/points/history?type=SPENT"))
                .bodyJson()
                .hasPathSatisfying("$.items.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.items[0].amount", v -> assertThat(v).isEqualTo(-400))
                .hasPathSatisfying("$.items[0].reason", v -> assertThat(v).isEqualTo("PURCHASE"))
                .hasPathSatisfying("$.items[0].label", v -> assertThat(v).isEqualTo("상품"));
        // 받은 것(픽스처 지급 1건)과 쓴 것 1건 — 한 쪽에 하나씩 넘기면 cursor로 이어진다
        assertThat(get(user, "/api/points/history?type=EARNED"))
                .bodyJson()
                .hasPathSatisfying("$.items.length()", v -> assertThat(v).isEqualTo(1))
                .hasPathSatisfying("$.items[0].amount", v -> assertThat(v).isEqualTo(1000));
        PointHistoryResponse first = pointService.history(user, PointHistoryType.ALL, null, 1, "ko");
        assertThat(first.items()).extracting(PointHistoryItem::amount).containsExactly(-400);
        PointHistoryResponse second = pointService.history(user, PointHistoryType.ALL, first.nextCursor(), 1, "ko");
        assertThat(second.items()).extracting(PointHistoryItem::amount).containsExactly(1000);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void 잔액이_모자라거나_잠긴_마스코트의_소품이거나_보상_상품이면_살_수_없다() {
        long user = fixture.member();
        fixture.givePoints(user, 100);

        assertCode(purchase(user, fixture.pricedItem(null, 101)), HttpStatus.CONFLICT, "INSUFFICIENT_POINTS");
        assertCode(purchase(user, fixture.pricedItem("CAMCORDER", 10)), HttpStatus.CONFLICT, "MASCOT_LOCKED");
        assertCode(purchase(user, fixture.rewardItem("STREAK_DAYS", 30)), HttpStatus.CONFLICT, "ITEM_NOT_PURCHASABLE");
        assertCode(purchase(user, 999_999_999L), HttpStatus.NOT_FOUND, "SHOP_ITEM_NOT_FOUND");
        assertCode(purchase(fixture.guest(), fixture.pricedItem(null, 1)), HttpStatus.FORBIDDEN, "MEMBER_ONLY");

        assertThat(get(user, "/api/points"))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(100));
    }

    @Test
    void 같은_구매가_동시에_와도_포인트는_한_번만_빠진다() throws Exception {
        long user = fixture.member();
        fixture.givePoints(user, 1000);
        long item = fixture.pricedItem(null, 400);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> buy = () -> {
            start.await();
            try {
                PurchaseResponse response = shopService.purchase(user, item);
                return response.balance() == 600;
            } catch (ConflictException e) {
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = List.of(executor.submit(buy), executor.submit(buy));
            start.countDown();
            assertThat(List.of(
                            results.get(0).get(10, TimeUnit.SECONDS),
                            results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(get(user, "/api/points"))
                .bodyJson()
                .hasPathSatisfying("$.balance", v -> assertThat(v).isEqualTo(600));
    }

    @Test
    void 가진_소품만_끼고_벗을_수_있다() {
        long user = fixture.member();
        fixture.givePoints(user, 1000);
        long crown = fixture.pricedItem("STOPWATCH", 100);
        long notOwned = fixture.pricedItem("STOPWATCH", 100);
        String uri = "/api/decorations/mascots/STOPWATCH/accessories";

        assertCode(
                put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":" + notOwned + "}"),
                HttpStatus.CONFLICT,
                "ITEM_NOT_OWNED");

        assertThat(purchase(user, crown)).hasStatusOk();
        assertThat(put(
                        user,
                        "/api/decorations/mascots/PENCIL/accessories",
                        "{\"slot\":\"HEAD\",\"itemId\":" + crown + "}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(user, uri, "{\"slot\":\"FACE\",\"itemId\":" + crown + "}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":" + crown + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("STOPWATCH"))
                .hasPathSatisfying(
                        "$.accessories[0].item.itemId", v -> assertThat(v).isEqualTo((int) crown));
        assertThat(inUse(user, crown)).isTrue();

        assertThat(put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":null}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.accessories.length()", v -> assertThat(v).isEqualTo(0));
        assertThat(inUse(user, crown)).isFalse();
    }

    @Test
    void 새_칸의_소품을_끼면_뒤_조각_그림이_함께_오고_상품에는_등급이_실린다() {
        long user = fixture.member();
        fixture.givePoints(user, 1000);
        long wings = fixture.pricedItem("STOPWATCH", 100);
        jdbc.sql("UPDATE shop_item SET slot = 'UNDERFOOT', back_image_url = 'https://cdn.test/wings-back.png',"
                        + " grade = 2 WHERE id = :id")
                .param("id", wings)
                .update();
        assertThat(purchase(user, wings)).hasStatusOk();

        assertThat(put(
                        user,
                        "/api/decorations/mascots/STOPWATCH/accessories",
                        "{\"slot\":\"UNDERFOOT\",\"itemId\":" + wings + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.accessories[0].slot", v -> assertThat(v).isEqualTo("UNDERFOOT"))
                .hasPathSatisfying(
                        "$.accessories[0].item.backImageUrl",
                        v -> assertThat(v).isEqualTo("https://cdn.test/wings-back.png"));
        assertThat(item(user, wings).grade()).isEqualTo(2);
        assertThat(item(user, wings).backImageUrl()).isEqualTo("https://cdn.test/wings-back.png");
        assertThat(item(user, fixture.pricedItem(null, 10)).grade()).isEqualTo(1);
    }

    @Test
    void 세트를_끼면_그_마스코트의_낱개_소품이_모두_풀리고_낱개를_끼면_세트가_풀린다() {
        long user = fixture.member();
        fixture.givePoints(user, 1000);
        long hat = fixture.pricedItem("STOPWATCH", 100);
        long otherMascotHat = fixture.pricedItem("PENCIL", 100);
        long set = effectSet();
        for (long owned : new long[] {set, otherMascotHat}) {
            jdbc.sql("INSERT INTO user_item (user_id, item_id) VALUES (:u, :i)")
                    .param("u", user)
                    .param("i", owned)
                    .update();
        }
        assertThat(purchase(user, hat)).hasStatusOk();
        String uri = "/api/decorations/mascots/STOPWATCH/accessories";
        put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":" + hat + "}");
        // 다른 마스코트의 소품은 건드리지 않는다
        jdbc.sql("INSERT INTO user_mascot_accessory (user_id, mascot_code, slot, item_id)"
                        + " VALUES (:u, 'PENCIL', 'HEAD', :i)")
                .param("u", user)
                .param("i", otherMascotHat)
                .update();

        assertThat(put(user, uri, "{\"slot\":\"SET\",\"itemId\":" + set + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.accessories.length()", v -> assertThat(v).isEqualTo(2))
                .hasPathSatisfying(
                        "$.accessories[?(@.slot=='SET')].item.effectImageUrl",
                        v -> assertThat(v).asList().containsExactly("https://cdn.test/set-effect.png"))
                .hasPathSatisfying(
                        "$.accessories[?(@.slot=='SET')].item.effectPieces[0].motion",
                        v -> assertThat(v).asList().containsExactly("float"))
                .hasPathSatisfying(
                        "$.accessories[?(@.slot=='SET')].item.effectType",
                        v -> assertThat(v).asList().containsExactly("aurora"));
        assertThat(inUse(user, hat)).isFalse();
        assertThat(inUse(user, otherMascotHat)).isTrue();
        assertThat(item(user, set).effectPieces()).singleElement().satisfies(piece -> {
            assertThat(piece.x()).isEqualTo(0.1);
            assertThat(piece.height()).isEqualTo(0.4);
            assertThat(piece.spriteUrl()).isNull();
        });
        assertSpriteFields(user, set);

        assertThat(put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":" + hat + "}")).hasStatusOk();
        assertThat(inUse(user, set)).isFalse();
        assertThat(inUse(user, hat)).isTrue();

        // 조각 값이 잘못 들어가도 상품 목록은 깨지지 않고 움직임만 빠진다
        jdbc.sql("UPDATE shop_item SET effect_pieces = CAST('[{\"x\":\"left\"}]' AS jsonb) WHERE id = :id")
                .param("id", set)
                .update();
        assertThat(item(user, set).effectPieces()).isNull();
    }

    @Test
    void 타이머_배경을_적용하고_기본으로_되돌린다() {
        long user = fixture.member();
        fixture.givePoints(user, 1000);
        long background = fixture.pricedItem(null, 100);
        assertThat(purchase(user, background)).hasStatusOk();

        assertThat(put(user, "/api/decorations/timer-background", "{\"itemId\":" + background + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.timerBackground.itemId", v -> assertThat(v).isEqualTo((int) background));
        assertThat(inUse(user, background)).isTrue();

        assertThat(put(user, "/api/decorations/timer-background", "{\"itemId\":null}"))
                .hasStatusOk();
        assertThat(inUse(user, background)).isFalse();
    }

    @Test
    void 보상_상품은_조건을_채우면_가진_것이_되어_적용할_수_있다() {
        long user = fixture.member();
        long reward = fixture.rewardItem("STREAK_DAYS", 3);
        String uri = "/api/decorations/timer-background";
        fixture.measuredSession(user, "2026-01-10", 1, 600);
        fixture.measuredSession(user, "2026-01-11", 1, 600);
        // 5분에 못 미친 날은 연속 일수에 들지 않는다
        fixture.measuredSession(user, "2026-01-12", 1, 200);

        assertCode(put(user, uri, "{\"itemId\":" + reward + "}"), HttpStatus.CONFLICT, "ITEM_NOT_OWNED");
        assertThat(item(user, reward).unlock().currentValue()).isEqualTo(2);
        assertThat(item(user, reward).owned()).isFalse();

        fixture.measuredSession(user, "2026-01-12", 3, 600);

        assertThat(item(user, reward).owned()).isTrue();
        assertThat(item(user, reward).price()).isNull();
        assertThat(put(user, uri, "{\"itemId\":" + reward + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.timerBackground.itemId", v -> assertThat(v).isEqualTo((int) reward));
    }

    /** 효과 그림과 움직임 정보가 있는 STOPWATCH의 세트 상품. */
    private long effectSet() {
        return jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name, store_product_id,
                            effect_image_url, effect_pieces, effect_type)
                        VALUES (:code, 'MASCOT_ACCESSORY', 'STOPWATCH', 'SET', '세트', :p, 'https://cdn.test/set-effect.png',
                            CAST('[{"x":0.1,"y":0.2,"width":0.3,"height":0.4,"motion":"float"}]' AS jsonb), 'aurora')
                        RETURNING id""")
                .param("code", "SET_" + java.util.UUID.randomUUID())
                .param("p", "set_" + java.util.UUID.randomUUID())
                .query(Long.class)
                .single();
    }

    private boolean inUse(long user, long itemId) {
        return item(user, itemId).inUse();
    }

    /** 상품 목록에서 그 상품 하나. */
    private ShopItemResponse item(long user, long itemId) {
        return shopService.list(user, "ko").items().stream()
                .filter(item -> item.id() == itemId)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 비회원은_상점과_꾸미기와_기록을_보기만_하고_사거나_끼지_못한다() {
        long guest = fixture.guest();
        long item = fixture.pricedItem(null, 1);

        assertThat(get(guest, "/api/shop/items")).hasStatusOk();
        assertThat(get(guest, "/api/decorations"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("STOPWATCH"));
        assertThat(get(guest, "/api/points/history"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items")
                .asArray()
                .isEmpty();
        assertCode(purchase(guest, item), HttpStatus.FORBIDDEN, "MEMBER_ONLY");
        assertCode(
                mvc.put()
                        .uri("/api/decorations/timer-background")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(guest))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":null}")
                        .exchange(),
                HttpStatus.FORBIDDEN,
                "MEMBER_ONLY");
    }

    /** 띠 그림 값이 있으면 같이 내려 준다. */
    private void assertSpriteFields(long user, long set) {
        jdbc.sql("""
                UPDATE shop_item SET effect_pieces = CAST('[{"x":0.1,"y":0.1,"width":0.8,"height":0.8,"motion":"pulse",
                  "spriteUrl":"https://cdn.test/fx-sprite.webp","spriteFrames":12,"spriteDurationMs":1200}]' AS jsonb)
                WHERE id = :id""").param("id", set).update();
        assertThat(item(user, set).effectPieces()).singleElement().satisfies(piece -> {
            assertThat(piece.spriteUrl()).isEqualTo("https://cdn.test/fx-sprite.webp");
            assertThat(piece.spriteFrames()).isEqualTo(12);
            assertThat(piece.spriteDurationMs()).isEqualTo(1200);
        });
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
    }

    private MvcTestResult purchase(long user, long itemId) {
        return mvc.post()
                .uri("/api/shop/items/" + itemId + "/purchase")
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }

    private MvcTestResult put(long user, String uri, String body) {
        return mvc.put()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult get(long user, String uri) {
        return mvc.get()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user))
                .exchange();
    }
}
