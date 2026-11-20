package project.study.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static project.study.support.AuthTestSupport.asUser;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.point.PointShopFixture;
import project.study.shop.dto.AssetType;
import project.study.shop.dto.ShopCategory;
import project.study.shop.dto.ShopItemResponse;
import project.study.shop.service.ShopService;

/** 세션 공간 상품: 공간 파일 정보, 무료 공간, 판매를 멈춘 상품. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShopSpaceItemApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    ShopService shopService;

    @Test
    void 무료_공간은_사지_않아도_가진_것이고_공간_파일_정보와_함께_바로_적용된다() {
        long user = fixture.member();
        long free = fixture.freeItem("watcher/9c8d9f19", "VIDEO");

        ShopItemResponse listed = item(user, free);
        assertThat(listed.owned()).isTrue();
        assertThat(listed.price()).isNull();
        assertThat(listed.assetKey()).isEqualTo("watcher/9c8d9f19");
        assertThat(listed.assetType()).isEqualTo(AssetType.VIDEO);
        assertCode(purchase(user, free), HttpStatus.CONFLICT, "ITEM_NOT_PURCHASABLE");

        assertThat(put(user, "/api/decorations/timer-background", "{\"itemId\":" + free + "}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.timerBackground.assetKey", v -> assertThat(v).isEqualTo("watcher/9c8d9f19"))
                .hasPathSatisfying(
                        "$.timerBackground.assetType", v -> assertThat(v).isEqualTo("VIDEO"));
    }

    @Test
    void 판매를_멈춘_상품은_가진_사람에게만_보이고_새로_살_수_없다() {
        long owner = fixture.member();
        long other = fixture.member();
        fixture.givePoints(owner, 1000);
        fixture.givePoints(other, 1000);
        long background = fixture.pricedItem(null, 100);
        assertThat(purchase(owner, background)).hasStatusOk();

        fixture.deactivate(background);

        assertThat(listed(owner, background))
                .hasValueSatisfying(item -> assertThat(item.owned()).isTrue());
        assertThat(listed(other, background)).isEmpty();
        assertCode(purchase(other, background), HttpStatus.NOT_FOUND, "SHOP_ITEM_NOT_FOUND");
    }

    @Test
    void 판매를_멈춘_무료_공간은_목록에서_빠지고_적용해_둔_사람은_바꾸기_전까지_그대로_쓴다() {
        long user = fixture.member();
        long free = fixture.freeItem("watcher/9c8d9f19", "VIDEO");
        String uri = "/api/decorations/timer-background";
        assertThat(put(user, uri, "{\"itemId\":" + free + "}")).hasStatusOk();

        fixture.deactivate(free);

        assertThat(listed(user, free)).isEmpty();
        assertThat(get(user, "/api/decorations"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.timerBackground.itemId", v -> assertThat(v).isEqualTo((int) free));
        assertCode(put(user, uri, "{\"itemId\":" + free + "}"), HttpStatus.CONFLICT, "ITEM_NOT_OWNED");
    }

    @Test
    void 판매를_멈춘_보상_상품은_조건을_채운_사람에게만_보인다() {
        long achiever = fixture.member();
        long other = fixture.member();
        long reward = fixture.rewardItem("TOTAL_FOCUS_HOURS", 1);
        fixture.measuredSession(achiever, "2026-01-10", 1, 3600);

        fixture.deactivate(reward);

        assertThat(listed(achiever, reward))
                .hasValueSatisfying(item -> assertThat(item.owned()).isTrue());
        assertThat(listed(other, reward)).isEmpty();
    }

    @Test
    void 비회원도_무료_공간을_가진_것으로_보지만_적용은_회원만_한다() {
        long guest = fixture.guest();
        long free = fixture.freeItem("watcher/9c8d9f19", "VIDEO");

        assertThat(item(guest, free).owned()).isTrue();
        assertCode(
                put(guest, "/api/decorations/timer-background", "{\"itemId\":" + free + "}"),
                HttpStatus.FORBIDDEN,
                "MEMBER_ONLY");
    }

    @Test
    void 무료_상품과_공간_파일_정보는_타이머_배경에만_넣을_수_있다() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name)
                        VALUES ('FREE_HAT', 'MASCOT_ACCESSORY', 'STOPWATCH', 'HEAD', '무료 모자')""").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, mascot_code, slot, name, price, asset_key, asset_type)
                        VALUES ('ASSET_HAT', 'MASCOT_ACCESSORY', 'STOPWATCH', 'HEAD', '모자', 100, 'hat/1', 'IMAGE')""").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO shop_item (code, category, name, asset_key)
                        VALUES ('HALF_ASSET', 'TIMER_BACKGROUND', '배경', 'bg/1')""").update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 독서실_감독관은_처음부터_카탈로그에_있는_무료_영상_공간이다() {
        long proctor = jdbc.sql("SELECT id FROM shop_item WHERE code = 'SPACE_PROCTOR'")
                .query(Long.class)
                .single();
        long user = fixture.member();

        ShopItemResponse ko = item(user, proctor);
        assertThat(ko.category()).isEqualTo(ShopCategory.TIMER_BACKGROUND);
        assertThat(ko.tag()).isEqualTo("STUDY_SPACE");
        assertThat(ko.name()).isEqualTo("독서실 감독관");
        assertThat(ko.owned()).isTrue();
        assertThat(ko.price()).isNull();
        assertThat(ko.unlock()).isNull();
        assertThat(ko.storeProductId()).isNull();
        assertThat(ko.assetKey()).isEqualTo("watcher/9c8d9f19");
        assertThat(ko.assetType()).isEqualTo(AssetType.VIDEO);
        // 앱 카탈로그(study-session.json의 proctor)와 같은 이름이어야 상점과 세션 화면이 같은 이름을 보여 준다
        assertThat(nameIn(user, proctor, "en")).isEqualTo("Study hall proctor");
        assertThat(nameIn(user, proctor, "ja")).isEqualTo("自習室の監督");
        assertThat(nameIn(user, proctor, "zh")).isEqualTo("自習室監督");
    }

    private String nameIn(long user, long itemId, String lang) {
        return shopService.list(user, lang).items().stream()
                .filter(item -> item.id() == itemId)
                .findFirst()
                .orElseThrow()
                .name();
    }

    /** 상품 목록에 그 상품이 있으면 그것. */
    private Optional<ShopItemResponse> listed(long user, long itemId) {
        return shopService.list(user, "ko").items().stream()
                .filter(item -> item.id() == itemId)
                .findFirst();
    }

    private ShopItemResponse item(long user, long itemId) {
        return listed(user, itemId).orElseThrow();
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
