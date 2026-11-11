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
        long crown = fixture.pricedItem("BOOK", 100);
        long notOwned = fixture.pricedItem("BOOK", 100);
        String uri = "/api/decorations/mascots/BOOK/accessories";

        assertCode(
                put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":" + notOwned + "}"),
                HttpStatus.CONFLICT,
                "ITEM_NOT_OWNED");

        assertThat(purchase(user, crown)).hasStatusOk();
        assertThat(put(
                        user,
                        "/api/decorations/mascots/PLANNER/accessories",
                        "{\"slot\":\"HEAD\",\"itemId\":" + crown + "}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(user, uri, "{\"slot\":\"FACE\",\"itemId\":" + crown + "}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(put(user, uri, "{\"slot\":\"HEAD\",\"itemId\":" + crown + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.selectedMascotCode", v -> assertThat(v).isEqualTo("BOOK"))
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
